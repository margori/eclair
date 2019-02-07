package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey

import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.wire.ChannelUpdate
import Router._
import fr.acinq.eclair.router.Graph.RoutingHeuristics.GraphStructure.GraphEdge

object Graph {

  // A compound weight for an edge, score is obtained with (cost X factor),'feeCost' contains the actual fees in millisatoshi, 'cltvCumulative' the total CLTV necessary to reach this edge
  case class Weight(score: Double, feeCostMsat: Long, cltvCumulative: Int)
  case class WeightRatios(cltvDeltaFactor: Double, ageFactor: Double, capacityFactor: Double) // The ratios that will be used to calculate the 'factor'
  case class WeightedNode(key: PublicKey, weight: Weight)
  case class WeightedPath(path: Seq[GraphEdge], weight: Weight)
  case class RichWeight(cost: Long, length: Int, cltv: Int)
  case class WeightedNode(key: PublicKey, weight: RichWeight)
  case class WeightedPath(path: Seq[GraphEdge], weight: RichWeight)

  /**
    * This comparator must be consistent with the "equals" behavior, thus for two weighted nodes with
    * the same weight we distinguish them by their public key. See https://docs.oracle.com/javase/8/docs/api/java/util/Comparator.html
    */
  object QueueComparator extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = {
      val weightCmp = x.weight.cost.compareTo(y.weight.cost)
      val weightCmp = x.weight.score.compareTo(y.weight.score)
      if (weightCmp == 0) x.key.toString().compareTo(y.key.toString())
      else weightCmp
    }
  }

  implicit object PathComparator extends Ordering[WeightedPath] {
    override def compare(x: WeightedPath, y: WeightedPath): Int = y.weight.cost.compareTo(x.weight.cost)
    override def compare(x: WeightedPath, y: WeightedPath): Int = y.weight.score.compare(x.weight.score)
  }
  /**
    * Yen's algorithm to find the k-shortest (loopless) paths in a graph, uses dijkstra as search algo. Is guaranteed to terminate finding
    * at most @pathsToFind paths sorted by cost (the cheapest is in position 0).
    * @param graph
    * @param sourceNode
    * @param targetNode
    * @param amountMsat
    * @param pathsToFind
    * @param wr an object containing the ratios used to 'weight' edges when searching for the shortest path
    * @param currentBlockHeight the height of the chain tip (latest block)
    * @param boundaries a predicate function that can be used to impose limits on the outcome of the search
    * @return
    */
  def yenKshortestPaths(graph: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, ignoredEdges: Set[ChannelDesc], extraEdges: Set[GraphEdge], pathsToFind: Int, boundaries: RichWeight => Boolean): Seq[WeightedPath] = {
  def yenKshortestPaths(graph: DirectedGraph,
                        sourceNode: PublicKey,
                        targetNode: PublicKey,
                        amountMsat: Long,
                        ignoredEdges: Set[ChannelDesc],
                        extraEdges: Set[GraphEdge],
                        pathsToFind: Int,
                        wr: WeightRatios,
                        currentBlockHeight: Long,
                        boundaries: Weight => Boolean): Seq[WeightedPath] = {

    var allSpurPathsFound = false

    // stores the shortest paths
    val shortestPaths = new mutable.MutableList[WeightedPath]
    // stores the candidates for k(K +1) shortest paths, sorted by path cost
    val candidates = new mutable.PriorityQueue[WeightedPath]

    // find the shortest path, k = 0
    val shortestPath = dijkstraShortestPath(graph, sourceNode, targetNode, amountMsat, ignoredEdges, extraEdges, RichWeight(amountMsat, 0, 0), boundaries)
    shortestPaths += WeightedPath(shortestPath, pathWeight(shortestPath, amountMsat, isPartial = false))

    // avoid returning a list with an empty path
    if(shortestPath.isEmpty) return Seq.empty
    val shortestPath = dijkstraShortestPath(graph, sourceNode, targetNode, amountMsat, ignoredEdges, extraEdges, wr, currentBlockHeight, boundaries, startingDistance = 0)
    shortestPaths += WeightedPath(shortestPath, pathWeight(shortestPath, amountMsat, graph, wr, currentBlockHeight))

    // main loop
    for(k <- 1 until pathsToFind) {

      if (!allSpurPathsFound) {

        // for every edge in the path
        for (i <- shortestPaths(k - 1).path.indices) {

          val prevShortestPath = shortestPaths(k - 1).path

          // select the spur node as the i-th element of the k-th previous shortest path (k -1)
          val spurEdge = prevShortestPath(i)

          // select the subpath from the source to the spur node of the k-th previous shortest path
          val rootPathEdges = if (i == 0) prevShortestPath.head :: Nil else prevShortestPath.take(i)
          val rootPathWeight = pathWeight(rootPathEdges, amountMsat, isPartial = true)
          val rootPathEdges = if(i == 0) prevShortestPath.head :: Nil else prevShortestPath.take(i)
          val rootPathLength = rootPathEdges.size

          // links to be removed that are part of the previous shortest path and which share the same root path
          val edgesToIgnore = shortestPaths.flatMap { weightedPath =>
            if ((i == 0 && (weightedPath.path.head :: Nil) == rootPathEdges) || weightedPath.path.take(i) == rootPathEdges) {
              weightedPath.path(i).desc :: Nil
            } else {
              Nil
            }
          }

          // find the "spur" path, a subpath going from the spur edge to the target avoiding previously found subpaths
          val spurPath = dijkstraShortestPath(graph, spurEdge.desc.a, targetNode, amountMsat, ignoredEdges ++ edgesToIgnore.toSet, extraEdges, rootPathWeight, boundaries)
          val spurPath = dijkstraShortestPath(graph, spurEdge.desc.a, targetNode, amountMsat, ignoredEdges ++ edgesToIgnore.toSet, extraEdges, wr, currentBlockHeight, boundaries, startingDistance = rootPathLength)

          // if there wasn't a path the spur will be empty
          if (spurPath.nonEmpty) {

            // candidate k-shortest path is made of the rootPath and the new spurPath
            val totalPath = rootPathEdges.head.desc.a == spurPath.head.desc.a match {
              case true => rootPathEdges.tail ++ spurPath // if the heads are the same node, drop it from the rootPath
              case false => rootPathEdges ++ spurPath
            }

            val candidatePath = WeightedPath(totalPath, pathWeight(totalPath, amountMsat, isPartial = false))

            if (boundaries(candidatePath.weight) && !shortestPaths.contains(candidatePath) && !candidates.exists(_ == candidatePath)) {
              candidates.enqueue(candidatePath)
            }

          }
        }
      }

      if (candidates.isEmpty) {
        // handles the case of having exhausted all possible spur paths and it's impossible to reach the target from the source
        allSpurPathsFound = true
      } else {
        // move the best candidate to the shortestPaths container
        shortestPaths += candidates.dequeue()
      }
    }

    shortestPaths
  }

  // computes the total compound weight of a path, which is the cumulative sum of all the weights of the edges in this path (except the first)
  def pathWeight(path: Seq[GraphEdge], amountMsat: Long, graph: DirectedGraph, wr: WeightRatios, currentBlockHeight: Long): Weight = {
    path.drop(1).foldRight(Weight(0, 0, 0)) { (edge, cost) =>
      edgeWeightCompound(amountMsat, edge, cost, isNeighborTarget = false, currentBlockHeight, wr)
    }
  }



  /**
    * Finds the shortest path in the graph, uses a modified version of Dijsktra's algorithm that computes
    * the shortest path from the target to the source (this is because we want to calculate the weight of the
    * edges correctly). The graph @param g is optimized for querying the incoming edges given a vertex.
    *
    * @param g the graph on which will be performed the search
    * @param sourceNode the starting node of the path we're looking for
    * @param targetNode the destination node of the path
    * @param amountMsat the amount (in millisatoshis) we want to transmit
    * @param ignoredEdges a list of edges we do not want to consider
    * @param extraEdges a list of extra edges we want to consider but are not currently in the graph
    * @param wr an object containing the ratios used to 'weight' edges when searching for the shortest path
    * @param currentBlockHeight the height of the chain tip (latest block)
    * @param boundaries a predicate function that can be used to impose limits on the outcome of the search
    * @return
    */

  def dijkstraShortestPath(g: DirectedGraph,
                           sourceNode: PublicKey,
                           targetNode: PublicKey,
                           amountMsat: Long,
                           ignoredEdges: Set[ChannelDesc],
                           extraEdges: Set[GraphEdge],
                           initialWeight: RichWeight,
                           boundaries: RichWeight => Boolean): Seq[GraphEdge] = {

    //  the graph does not contain source/destination nodes
    if (!g.containsVertex(sourceNode)) return Seq.empty
    if (!g.containsVertex(targetNode) && (extraEdges.nonEmpty && !extraEdges.exists(_.desc.b == targetNode))) return Seq.empty

    val maxMapSize = 100 // conservative estimation to avoid over allocating memory

    // this is not the actual optimal size for the maps, because we only put in there all the vertices in the worst case scenario.
    val cost = new java.util.HashMap[PublicKey, RichWeight](maxMapSize)
    val weight = new java.util.HashMap[PublicKey, Weight](maxMapSize)
    val prev = new java.util.HashMap[PublicKey, GraphEdge](maxMapSize)
    val vertexQueue = new org.jheaps.tree.SimpleFibonacciHeap[WeightedNode, Short](QueueComparator)

    // initialize the queue and cost array with the initial weight
    cost.put(targetNode, initialWeight)
    vertexQueue.insert(WeightedNode(targetNode, initialWeight))
    // initialize the queue and weight array with the base cost (amount to be routed)
    val startingWeight = Weight(0, 0, 0)
    weight.put(targetNode, startingWeight)
    vertexQueue.insert(WeightedNode(targetNode, startingWeight))
    pathLength.put(targetNode, startingDistance) // the source node has distance 0

    var targetFound = false

    while (!vertexQueue.isEmpty && !targetFound) {

      // node with the smallest distance from the source
      val current = vertexQueue.deleteMin().getKey // O(log(n))

      if (current.key != sourceNode) {

        // build the neighbors with optional extra edges
        val currentNeighbors = extraEdges.isEmpty match {
          case true => g.getIncomingEdgesOf(current.key)
          case false =>
            val extraNeighbors = extraEdges.filter(_.desc.b == current.key)
            // the resulting set must have only one element per shortChannelId
            g.getIncomingEdgesOf(current.key).filterNot(e => extraNeighbors.exists(_.desc.shortChannelId == e.desc.shortChannelId)) ++ extraNeighbors
        }

        val currentWeight = cost.get(current.key)

        // for each neighbor
        currentNeighbors.foreach { edge =>

          val neighbor = edge.desc.a

          // note: 'cost' contains the smallest known cumulative cost (amount + fees) necessary to reach 'current' so far
          // note: there is always an entry for the current in the 'cost' map
          val newMinimumKnownWeight = RichWeight(
            cost = edgeWeight(edge, currentWeight.cost, initialWeight.length == 0 && neighbor == sourceNode),
            length = currentWeight.length + 1,
            cltv = currentWeight.cltv + edge.update.cltvExpiryDelta
          )

          // test for ignored edges
          if (edge.update.htlcMaximumMsat.forall(newMinimumWeight.feeCostMsat + amountMsat <= _) &&
            newMinimumWeight.feeCostMsat + amountMsat >= edge.update.htlcMinimumMsat &&
            neighborPathLength <= ROUTE_MAX_LENGTH && // ignore this edge if it would make the path too long
            boundaries(newMinimumWeight) && // check if this neighbor edge would break off the 'boundaries'
            !ignoredEdges.contains(edge.desc)
          ) {

            // we call containsKey first because "getOrDefault" is not available in JDK7
            val neighborCost = cost.containsKey(neighbor) match {
              case false => Long.MaxValue
              case true => cost.get(neighbor)
            }

            // if this neighbor has a shorter distance than previously known
            if (newMinimumKnownCost < neighborCost) {

              // update the total length of this partial path
              pathLength.put(neighbor, neighborPathLength)

              // update the visiting tree
              prev.put(neighbor, edge)

              // update the queue
              vertexQueue.insert(WeightedNode(neighbor, newMinimumKnownCost)) // O(1)

              // update the minimum known distance array
              cost.put(neighbor, newMinimumKnownCost)
            }
          }
        }
      } else { // we popped the target node from the queue, no need to search any further
        targetFound = true
      }
    }

    targetFound match {
      case false => Seq.empty[GraphEdge]
      case true =>
        // we traverse the list of "previous" backward building the final list of edges that make the shortest path
        val edgePath = new mutable.ArrayBuffer[GraphEdge](DEFAULT_ROUTE_MAX_LENGTH)
        var current = prev.get(sourceNode)

        while (current != null) {

          edgePath += current
          current = prev.get(current.desc.b)
        }

        edgePath
    }
  }

  // Computes the compound weight for the given @param edge, the weight is cumulative and must account for the previous edge's weight.
  private def edgeWeightCompound(amountMsat: Long, edge: GraphEdge, prev: Weight, isNeighborTarget: Boolean, currentBlockHeight: Long, wr: WeightRatios): Weight = {
    import RoutingHeuristics._

    // Every edge is weighted by funding block height where older blocks add less weight, the window considered is 2 months.
    val channelBlockHeight = ShortChannelId.coordinates(edge.desc.shortChannelId).blockHeight
    val ageFactor = normalize(channelBlockHeight, min = currentBlockHeight - BLOCK_TIME_TWO_MONTHS, max = currentBlockHeight)

    // Every edge is weighted by channel capacity, but larger channels add less weight
    val edgeMaxCapacity = edge.update.htlcMaximumMsat.getOrElse(CAPACITY_CHANNEL_LOW_MSAT)
    val capFactor = 1 - normalize(edgeMaxCapacity, CAPACITY_CHANNEL_LOW_MSAT, CAPACITY_CHANNEL_HIGH_MSAT)

    // Every edge is weighted by its clvt-delta value, normalized
    val channelCltvDelta = edge.update.cltvExpiryDelta
    val cltvFactor = normalize(channelCltvDelta, CLTV_LOW, CLTV_HIGH)

    // NB. 'edgeFees' here is only the fee that must be paid to traverse this @param edge
    val edgeFees = if(isNeighborTarget) 0 else edgeCost(edge, amountMsat + prev.feeCostMsat) - amountMsat

    val factor = (cltvFactor * wr.cltvDeltaFactor) + (ageFactor * wr.ageFactor) + (capFactor * wr.capacityFactor) match {
      case 0 => 0.00001 // if the factor turns out to be 0 we default to a very small number to avoid having a weight of 0 and still take into account the cost
      case other => other
    }

    Weight(edgeFees * factor, edgeFees, prev.cltvCumulative + channelCltvDelta)
  }

  /**
    *
    * @param edge the edge for which we want to compute the weight
    * @param amountWithFees the value that this edge will have to carry along
    * @param isNeighborTarget true if the receiving vertex of this edge is the target node (source in a reversed graph), which has cost 0
    * @return the new amount updated with the necessary fees for this edge
    */
  private def edgeCost(edge: GraphEdge, amountWithFees: Long): Long = {
    amountWithFees + nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountWithFees)
  }

  object RoutingHeuristics {

    // Number of blocks in two months
    val BLOCK_TIME_TWO_MONTHS = 8640

    // Low/High bound for channel capacity
    val CAPACITY_CHANNEL_LOW_MSAT = 1000 * 1000L // 1000 sat
    val CAPACITY_CHANNEL_HIGH_MSAT = Channel.MAX_FUNDING_SATOSHIS * 1000L

    // Low/High bound for CLTV channel value
    val CLTV_LOW = 9
    val CLTV_HIGH = 2016

    def normalize(value: Double, min: Double, max: Double) = {
      if(value <= min) 0D
      else if (value > max) 1D
      else (value - min) / (max - min)
    }
  private def edgeWeight(edge: GraphEdge, amountWithFees: Long, isNeighborSource: Boolean): Long = isNeighborSource match {
    case false => amountWithFees + nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountWithFees)
    case true => amountWithFees
  }

  // Calculates the total cost of a path (amount + fees), direct channels with the source will have a cost of 0 (pay no fees)
  def pathWeight(path: Seq[GraphEdge], amountMsat: Long, isPartial: Boolean): RichWeight = {
    path.drop(if(isPartial) 0 else 1).foldRight(RichWeight(amountMsat, 0, 0)) { (edge, prev) =>
      RichWeight(
        cost = edgeWeight(edge, prev.cost, isNeighborSource = false),
        cltv = prev.cltv + edge.update.cltvExpiryDelta,
        length = prev.length + 1
      )
    }
  }

  /**
    * A graph data structure that uses the adjacency lists, stores the incoming edges of the neighbors
    */
  object GraphStructure {

    /**
      * Representation of an edge of the graph
      *
      * @param desc channel description
      * @param update channel info
      */
    case class GraphEdge(desc: ChannelDesc, update: ChannelUpdate)

    case class DirectedGraph(private val vertices: Map[PublicKey, List[GraphEdge]]) {

      def addEdge(d: ChannelDesc, u: ChannelUpdate): DirectedGraph = addEdge(GraphEdge(d, u))

      def addEdges(edges: Seq[(ChannelDesc, ChannelUpdate)]): DirectedGraph = {
        edges.foldLeft(this)((acc, edge) => acc.addEdge(edge._1, edge._2))
      }

      /**
        * Adds and edge to the graph, if one of the two vertices is not found, it will be created.
        *
        * @param edge the edge that is going to be added to the graph
        * @return a new graph containing this edge
        */
      def addEdge(edge: GraphEdge): DirectedGraph = {

        val vertexIn = edge.desc.a
        val vertexOut = edge.desc.b

        // the graph is allowed to have multiple edges between the same vertices but only one per channel
        if (containsEdge(edge.desc)) {
          removeEdge(edge.desc).addEdge(edge) // the recursive call will have the original params
        } else {
          val withVertices = addVertex(vertexIn).addVertex(vertexOut)
          DirectedGraph(withVertices.vertices.updated(vertexOut, edge +: withVertices.vertices(vertexOut)))
        }
      }

      /**
        * Removes the edge corresponding to the given pair channel-desc/channel-update,
        * NB: this operation does NOT remove any vertex
        *
        * @param desc the channel description associated to the edge that will be removed
        * @return
        */
      def removeEdge(desc: ChannelDesc): DirectedGraph = {
        containsEdge(desc) match {
          case true => DirectedGraph(vertices.updated(desc.b, vertices(desc.b).filterNot(_.desc == desc)))
          case false => this
        }
      }

      def removeEdges(descList: Seq[ChannelDesc]): DirectedGraph = {
        descList.foldLeft(this)((acc, edge) => acc.removeEdge(edge))
      }

      /**
        * @param edge
        * @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

      def getEdge(desc: ChannelDesc): Option[GraphEdge] = {
        vertices.get(desc.b).flatMap { adj =>
          adj.find(e => e.desc.shortChannelId == desc.shortChannelId && e.desc.a == desc.a)
        }
      }

      /**
        * @param keyA the key associated with the starting vertex
        * @param keyB the key associated with the ending vertex
        * @return all the edges going from keyA --> keyB (there might be more than one if it refers to different shortChannelId)
        */
      def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
        vertices.get(keyB) match {
          case None => Seq.empty
          case Some(adj) => adj.filter(e => e.desc.a == keyA)
        }
      }

      /**
        * The the incoming edges for vertex @param keyB
        * @param keyB
        * @return
        */
      def getIncomingEdgesOf(keyB: PublicKey): Seq[GraphEdge] = {
        vertices.getOrElse(keyB, List.empty)
      }

      /**
        * Removes a vertex and all it's associated edges (both incoming and outgoing)
        *
        * @param key
        * @return
        */
      def removeVertex(key: PublicKey): DirectedGraph = {
        DirectedGraph(removeEdges(getIncomingEdgesOf(key).map(_.desc)).vertices - key)
      }

      /**
        * Adds a new vertex to the graph, starting with no edges
        *
        * @param key
        * @return
        */
      def addVertex(key: PublicKey): DirectedGraph = {
        vertices.get(key) match {
          case None => DirectedGraph(vertices + (key -> List.empty))
          case _ => this
        }
      }

      /**
        * Note this operation will traverse all edges in the graph (expensive)
        * @param key
        * @return a list of the outgoing edges of vertex @param key, if the edge doesn't exists an empty list is returned
        */
      def edgesOf(key: PublicKey): Seq[GraphEdge] = {
        edgeSet().filter(_.desc.a == key).toSeq
      }

      /**
        * @return the set of all the vertices in this graph
        */
      def vertexSet(): Set[PublicKey] = vertices.keySet

      /**
        * @return an iterator of all the edges in this graph
        */
      def edgeSet(): Iterable[GraphEdge] = vertices.values.flatten

      /**
        * @param key
        * @return true if this graph contain a vertex with this key, false otherwise
        */
      def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

      /**
        * @param desc
        * @return true if this edge desc is in the graph. For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def containsEdge(desc: ChannelDesc): Boolean = {
        vertices.get(desc.b) match {
          case None => false
          case Some(adj) => adj.exists(neighbor => neighbor.desc.shortChannelId == desc.shortChannelId && neighbor.desc.a == desc.a)
        }
      }

      def prettyPrint(): String = {
        vertices.foldLeft("") { case (acc, (vertex, adj)) =>
          acc + s"[${vertex.toString().take(5)}]: ${adj.map("-> " + _.desc.b.toString().take(5))} \n"
        }
      }
    }

    object DirectedGraph {

      // convenience constructors
      def apply(): DirectedGraph = new DirectedGraph(Map())

      def apply(key: PublicKey): DirectedGraph = new DirectedGraph(Map(key -> List.empty))

      def apply(edge: GraphEdge): DirectedGraph = new DirectedGraph(Map()).addEdge(edge.desc, edge.update)

      def apply(edges: Seq[GraphEdge]): DirectedGraph = {
        makeGraph(edges.map(e => e.desc -> e.update).toMap)
      }

      // optimized constructor
      def makeGraph(descAndUpdates: Map[ChannelDesc, ChannelUpdate]): DirectedGraph = {

        // initialize the map with the appropriate size to avoid resizing during the graph initialization
        val mutableMap = new {} with mutable.HashMap[PublicKey, List[GraphEdge]] {
          override def initialSize: Int = descAndUpdates.size + 1
        }

        // add all the vertices and edges in one go
        descAndUpdates.foreach { case (desc, update) =>
          // create or update vertex (desc.b) and update its neighbor
          mutableMap.put(desc.b, GraphEdge(desc, update) +: mutableMap.getOrElse(desc.b, List.empty[GraphEdge]))
          mutableMap.get(desc.a) match {
            case None => mutableMap += desc.a -> List.empty[GraphEdge]
            case _ =>
          }
        }

        new DirectedGraph(mutableMap.toMap)
      }

      def graphEdgeToHop(graphEdge: GraphEdge): Hop = Hop(graphEdge.desc.a, graphEdge.desc.b, graphEdge.update)
    }

  }
}
