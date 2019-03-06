package fr.acinq.eclair.channel

import akka.event.LoggingAdapter
import fr.acinq.bitcoin._
import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.crypto.{Generators, KeyManager, ShaChain}
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.{CommitmentSpec, Transactions}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.{CommitSig, RevokeAndAck, UpdateFee, UpdateMessage}

case class CommitmentV1(localParams: LocalParams, remoteParams: RemoteParams,
                        channelFlags: Byte,
                        localCommit: LocalCommit, remoteCommit: RemoteCommit,
                        localChanges: LocalChanges, remoteChanges: RemoteChanges,
                        localNextHtlcId: Long, remoteNextHtlcId: Long,
                        originChannels: Map[Long, Origin], // for outgoing htlcs relayed through us, the id of the previous channel
                        remoteNextCommitInfo: Either[WaitingForRevocation, Point],
                        commitInput: InputInfo,
                        remotePerCommitmentSecrets: ShaChain, channelId: BinaryData) extends Commitments {

  override def getContext: CommitmentContext = ContextCommitmentV1

  override def addLocalProposal(proposal: UpdateMessage): Commitments = this.copy(localChanges = localChanges.copy(proposed = localChanges.proposed :+ proposal))

  override def addRemoteProposal(proposal: UpdateMessage): Commitments = this.copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed :+ proposal))

  def sendFee(cmd: CMD_UPDATE_FEE): (Commitments, UpdateFee) = {
    if (!localParams.isFunder) {
      throw FundeeCannotSendUpdateFee(channelId)
    }

    // let's compute the current commitment *as seen by them* with this change taken into account
    val fee = UpdateFee(channelId, cmd.feeratePerKw)
    // update_fee replace each other, so we can remove previous ones
    val commitments1 = this.copy(localChanges = localChanges.copy(proposed = localChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
    val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    // we look from remote's point of view, so if local is funder remote doesn't pay the fees
    val fees = commitTxFee(Satoshi(commitments1.remoteParams.dustLimitSatoshis), reduced).amount // we update the fee only in NON simplified commitment
    val missing = reduced.toRemoteMsat / 1000 - commitments1.remoteParams.channelReserveSatoshis - fees
    if (missing < 0) {
      throw CannotAffordFees(channelId, missingSatoshis = -1 * missing, reserveSatoshis = commitments1.localParams.channelReserveSatoshis, feesSatoshis = fees)
    }

    (commitments1, fee)
  }

  def receiveFee(fee: UpdateFee, maxFeerateMismatch: Double): Commitments = {
    if (localParams.isFunder) {
      throw FundeeCannotSendUpdateFee(channelId)
    }

    if (fee.feeratePerKw < fr.acinq.eclair.MinimumFeeratePerKw) {
      throw FeerateTooSmall(channelId, remoteFeeratePerKw = fee.feeratePerKw)
    }

    val localFeeratePerKw = Globals.feeratesPerKw.get.blocks_2
    if (Helpers.isFeeDiffTooHigh(fee.feeratePerKw, localFeeratePerKw, maxFeerateMismatch)) {
      throw FeerateTooDifferent(channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = fee.feeratePerKw)
    }

    // NB: we check that the funder can afford this new fee even if spec allows to do it at next signature
    // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
    // and it would be tricky to check if the conditions are met at signing
    // (it also means that we need to check the fee of the initial commitment tx somewhere)

    // let's compute the current commitment *as seen by us* including this change
    // update_fee replace each other, so we can remove previous ones
    val commitments1 = this.copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
    val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    val fees = commitTxFee(Satoshi(commitments1.remoteParams.dustLimitSatoshis), reduced).amount // we update the fee only in NON simplified
    val missing = reduced.toRemoteMsat / 1000 - commitments1.localParams.channelReserveSatoshis - fees
    if (missing < 0) {
      throw CannotAffordFees(channelId, missingSatoshis = -1 * missing, reserveSatoshis = commitments1.localParams.channelReserveSatoshis, feesSatoshis = fees)
    }

    commitments1
  }

  override def receiveCommit(commit: CommitSig, keyManager: KeyManager)(implicit log: LoggingAdapter): (Commitments, RevokeAndAck) =
    super.receiveCommit(commit, keyManager, htlcSigHashFlag = SIGHASH_ALL)

  override def sendCommit(keyManager: KeyManager)(implicit log: LoggingAdapter): (Commitments, CommitSig) =
    super.sendCommit(keyManager, htlcSigHashFlag = SIGHASH_ALL)

  override def makeLocalTxs(keyManager: KeyManager, commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, localPerCommitmentPoint: Point, remotePerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    CommitmentV1.makeLocalTxs(keyManager, commitTxNumber, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, remotePerCommitmentPoint, spec)
  }

  override def makeRemoteTxs(keyManager: KeyManager, commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, remotePerCommitmentPoint: Point, localPerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    CommitmentV1.makeRemoteTxs(keyManager, commitTxNumber, localParams, remoteParams, commitmentInput, remotePerCommitmentPoint, localPerCommitmentPoint, spec)
  }

  override def commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = CommitmentV1.commitTxFee(dustLimit, spec)
}

object CommitmentV1 {

  def makeLocalTxs(keyManager: KeyManager, commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, localPerCommitmentPoint: Point, remotePerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localDelayedPaymentPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(localParams.channelKeyPath).publicKey, localPerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(localParams.channelKeyPath).publicKey, localPerCommitmentPoint)
    val remotePaymentPubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val commitTx = Transactions.makeCommitmentV1CommitTx(commitmentInput, commitTxNumber, keyManager.paymentPoint(localParams.channelKeyPath).publicKey, remoteParams.paymentBasepoint, localParams.isFunder, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, remoteDelayedPaymentPubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, spec)(ContextCommitmentV1)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(keyManager: KeyManager, commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, remotePerCommitmentPoint: Point, localPerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(localParams.channelKeyPath).publicKey, remotePerCommitmentPoint)
    val localDelayedPaymentPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(localParams.channelKeyPath).publicKey, localPerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(localParams.channelKeyPath).publicKey, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(localParams.channelKeyPath).publicKey, remotePerCommitmentPoint)
    val commitTx = Transactions.makeCommitmentV1CommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, keyManager.paymentPoint(localParams.channelKeyPath).publicKey, !localParams.isFunder, Satoshi(remoteParams.dustLimitSatoshis), remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, localDelayedPaymentPubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(remoteParams.dustLimitSatoshis), remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, spec)(ContextCommitmentV1)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = {
    val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec)(ContextCommitmentV1)
    val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec)(ContextCommitmentV1)
    weight2fee(spec.feeratePerKw , commitWeight + 172 * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size))
  }


}