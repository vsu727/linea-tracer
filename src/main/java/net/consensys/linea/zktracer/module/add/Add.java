/*
 * Copyright ConsenSys AG.
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

package net.consensys.linea.zktracer.module.add;

import java.math.BigInteger;
import java.util.List;

import net.consensys.linea.zktracer.bytes.Bytes16;
import net.consensys.linea.zktracer.bytes.UnsignedByte;
import net.consensys.linea.zktracer.bytestheta.BaseBytes;
import net.consensys.linea.zktracer.module.Module;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.opcode.OpCodeData;
import net.consensys.linea.zktracer.opcode.OpCodes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;

/** Implementation of a {@link Module} for addition/subtraction. */
public class Add implements Module {
  public static final String ADD_JSON_KEY = "add";
  final Trace.TraceBuilder builder = Trace.builder();
  private int stamp = 0;

  @Override
  public String jsonKey() {
    return ADD_JSON_KEY;
  }

  @Override
  public final List<OpCode> supportedOpCodes() {
    return List.of(OpCode.ADD, OpCode.SUB);
  }

  @Override
  public void trace(MessageFrame frame) {
    final Bytes32 arg1 = Bytes32.leftPad(frame.getStackItem(0));
    final Bytes32 arg2 = Bytes32.leftPad(frame.getStackItem(1));

    final Bytes16 arg1Hi = Bytes16.wrap(arg1.slice(0, 16));
    final Bytes32 arg1Lo = Bytes32.leftPad(arg1.slice(16));
    final Bytes16 arg2Hi = Bytes16.wrap(arg2.slice(0, 16));
    final Bytes16 arg2Lo = Bytes16.wrap(arg2.slice(16));

    boolean overflowHi = false;
    boolean overflowLo;

    final OpCodeData opCodeData = OpCodes.of(frame.getCurrentOperation().getOpcode());
    final OpCode opCode = opCodeData.mnemonic();

    final BaseBytes res = Adder.addSub(opCode, arg1, arg2);

    final Bytes16 resHi = res.getHigh();
    final Bytes16 resLo = res.getLow();

    UInt256 arg1Int = UInt256.fromBytes(arg1);
    UInt256 arg2Int = UInt256.fromBytes(arg2);
    UInt256 resultBytes;

    if (opCode == OpCode.ADD) {
      resultBytes = arg1Int.add(arg2Int);
      if (resultBytes.compareTo(arg1Int) < 0) {
        overflowHi = true;
      }
    } else if (opCode == OpCode.SUB) {
      if (arg1Int.compareTo(arg2Int) < 0) {
        overflowHi = true;
      }
    }

    // check if the result is greater than 2^128
    final UInt256 twoToThe128 = UInt256.ONE.shiftLeft(128);

    stamp++;

    for (int i = 0; i < 16; i++) {
      Bytes32 addRes;
      if (opCode == OpCode.ADD) {
        addRes = Bytes32.wrap((UInt256.fromBytes(arg1Lo)).add(UInt256.fromBytes(arg2Lo)));
      } else {
        addRes = Bytes32.wrap((UInt256.fromBytes(resLo)).add(UInt256.fromBytes(arg2Lo)));
      }

      overflowLo = (addRes.compareTo(twoToThe128) >= 0);

      builder
          .acc1(resHi.slice(0, 1 + i).toUnsignedBigInteger())
          .acc2(resLo.slice(0, 1 + i).toUnsignedBigInteger())
          .arg1Hi(arg1Hi.toUnsignedBigInteger())
          .arg1Lo(arg1Lo.toUnsignedBigInteger())
          .arg2Hi(arg2Hi.toUnsignedBigInteger())
          .arg2Lo(arg2Lo.toUnsignedBigInteger())
          .byte1(UnsignedByte.of(resHi.get(i)))
          .byte2(UnsignedByte.of(resLo.get(i)))
          .ct(BigInteger.valueOf(i))
          .inst(BigInteger.valueOf(opCodeData.value()))
          .overflow(overflowBit(i, overflowHi, overflowLo))
          .resHi(resHi.toUnsignedBigInteger())
          .resLo(resLo.toUnsignedBigInteger())
          .stamp(BigInteger.valueOf(stamp))
          .validateRow();
    }
  }

  @Override
  public Object commit() {
    return new AddTrace(builder.build());
  }

  private boolean overflowBit(
      final int counter, final boolean overflowHi, final boolean overflowLo) {
    if (counter == 14) {
      return overflowHi;
    }

    if (counter == 15) {
      return overflowLo;
    }

    return false; // default bool value in go
  }
}