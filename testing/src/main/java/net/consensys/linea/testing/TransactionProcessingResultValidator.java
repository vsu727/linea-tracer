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

package net.consensys.linea.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;

@FunctionalInterface
public interface TransactionProcessingResultValidator {

  void accept(Transaction transaction, TransactionProcessingResult transactionProcessingResult);

  TransactionProcessingResultValidator DEFAULT_VALIDATOR =
      (t, r) -> {
        assertTrue(
            r.isSuccessful(),
            () ->
                "Transaction: %s not successful. %s"
                    .formatted(t.getHash().toString(), r.toString()));
      };

  TransactionProcessingResultValidator EMPTY_VALIDATOR = (t, r) -> {};
}
