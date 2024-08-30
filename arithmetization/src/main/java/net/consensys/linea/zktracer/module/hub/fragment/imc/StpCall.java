/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package net.consensys.linea.zktracer.module.hub.fragment.imc;

import java.math.BigInteger;

import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.consensys.linea.zktracer.module.constants.GlobalConstants;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.Trace;
import net.consensys.linea.zktracer.module.hub.fragment.TraceSubFragment;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.types.EWord;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.Words;

@Getter
@Setter
@Accessors(fluent = true)
public class StpCall implements TraceSubFragment {
  final Hub hub;
  final long memoryExpansionGas;
  OpCode opCode;
  long gasActual;
  EWord gas; // for CALL's only
  EWord value;
  boolean exists;
  boolean warm;
  long upfrontGasCost;
  boolean outOfGasException;
  long gasPaidOutOfPocket;
  long stipend;

  public StpCall(Hub hub, long memoryExpansionGas) {
    this.hub = hub;
    this.memoryExpansionGas = memoryExpansionGas;
    this.opCode = hub.opCode();
    this.gasActual = hub.messageFrame().getRemainingGas();
    Preconditions.checkArgument(this.opCode.isCall() || this.opCode.isCreate());

    if (this.opCode.isCall()) {
      this.stpCallForCalls(hub);
    } else {
      this.stpCallForCreates(hub);
    }
  }

  private void stpCallForCalls(Hub hub) {
    MessageFrame frame = hub.messageFrame();

    final boolean callCanTransferValue = this.opCode.callCanTransferValue();
    final Address to = Words.toAddress(frame.getStackItem(1));
    Account toAccount = frame.getWorldUpdater().getAccount(to);
    this.gas = EWord.of(frame.getStackItem(0));
    this.value = (callCanTransferValue) ? EWord.of(frame.getStackItem(2)) : EWord.ZERO;
    this.exists =
        switch (hub.opCode()) {
          case CALL, STATICCALL -> toAccount != null
              ? !toAccount.isEmpty()
              : false; // the address that matters here is that of the callee
          case CALLCODE,
              DELEGATECALL -> true; // the address that matters here is that of the caller --- who
            // always exists
            // TODO: @Olivier or @François:
            //  replace this with the same logic above with the
            //  current account (frame.getRecipientAddress() ?)
            //  also add arg check verifying existence == true
            //  in that case.
          default -> throw new IllegalArgumentException(
              "STP module triggered for a non CALL-type instruction");
        };
    this.warm = frame.isAddressWarm(to);

    final boolean isCALL = this.opCode.equals(OpCode.CALL);
    final boolean nonzeroValueTransfer = !value.isZero();

    this.upfrontGasCost = upfrontGasCostForCalls(isCALL, nonzeroValueTransfer);
    this.outOfGasException = this.gasActual < this.upfrontGasCost;
    this.gasPaidOutOfPocket = this.gasPaidOutOfPocketForCalls();
    this.stipend =
        !outOfGasException && nonzeroValueTransfer ? GlobalConstants.GAS_CONST_G_CALL_STIPEND : 0;
  }

  private long gasPaidOutOfPocketForCalls() {
    if (outOfGasException) {
      return gasPaidOutOfPocket = 0;
    } else {
      long gasMinusUpfront = gasActual - upfrontGasCost;
      long oneSixtyFourths = gasMinusUpfront >> 6;
      long maxGasAllowance = gasMinusUpfront - oneSixtyFourths;
      return gas().toUnsignedBigInteger().compareTo(BigInteger.valueOf(maxGasAllowance)) > 0
          ? maxGasAllowance
          : gas.toLong();
    }
  }

  private void stpCallForCreates(Hub hub) {
    MessageFrame frame = hub.messageFrame();

    this.gas = EWord.ZERO; // irrelevant
    this.value = EWord.of(frame.getStackItem(0));
    this.exists = false; // irrelevant
    this.warm = false; // irrelevant
    this.upfrontGasCost = GlobalConstants.GAS_CONST_G_CREATE + this.memoryExpansionGas;
    this.outOfGasException = this.gasActual < this.upfrontGasCost;
    this.gasPaidOutOfPocket = this.computeGasPaidOutOfPocketForCreates();
    this.stipend = 0; // irrelevant
  }

  private long computeGasPaidOutOfPocketForCreates() {
    if (outOfGasException) {
      return 0;
    } else {
      long gasMinusUpfrontCost = gasActual - upfrontGasCost;
      return gasMinusUpfrontCost - (gasMinusUpfrontCost >> 6);
    }
  }

  private long upfrontGasCostForCalls(boolean isCALL, boolean nonzeroValueTransfer) {

    boolean toIsWarm = this.warm();
    long upfrontGasCost = this.memoryExpansionGas;
    final boolean callWouldLeadToAccountCreation = isCALL && nonzeroValueTransfer && !this.exists;
    if (nonzeroValueTransfer) upfrontGasCost += GlobalConstants.GAS_CONST_G_CALL_VALUE;
    if (toIsWarm) upfrontGasCost += GlobalConstants.GAS_CONST_G_WARM_ACCESS;
    else upfrontGasCost += GlobalConstants.GAS_CONST_G_COLD_ACCOUNT_ACCESS;
    if (callWouldLeadToAccountCreation) upfrontGasCost += GlobalConstants.GAS_CONST_G_NEW_ACCOUNT;

    return upfrontGasCost;
  }

  @Override
  public Trace trace(Trace trace) {
    return trace
        .pMiscStpFlag(true)
        .pMiscStpInstruction(opCode.byteValue() & 0xff)
        .pMiscStpGasHi(gas.hi())
        .pMiscStpGasLo(gas.lo())
        .pMiscStpValueHi(value.hi())
        .pMiscStpValueLo(value.lo())
        .pMiscStpExists(exists)
        .pMiscStpWarmth(warm)
        .pMiscStpOogx(outOfGasException)
        .pMiscStpGasMxp(Bytes.ofUnsignedLong(memoryExpansionGas))
        .pMiscStpGasUpfrontGasCost(Bytes.ofUnsignedLong(upfrontGasCost))
        .pMiscStpGasPaidOutOfPocket(Bytes.ofUnsignedLong(gasPaidOutOfPocket))
        .pMiscStpGasStipend(stipend);
  }
}