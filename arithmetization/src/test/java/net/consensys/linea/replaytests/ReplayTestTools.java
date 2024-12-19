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

package net.consensys.linea.replaytests;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import lombok.extern.slf4j.Slf4j;
import net.consensys.linea.UnitTestWatcher;
import net.consensys.linea.testing.ReplayExecutionEnvironment;
import net.consensys.linea.zktracer.ZkTracer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Replays are captured on a fully (not snapshot) synchronized Besu node running the plugin:
 *
 * <pre>{@code
 * curl -X POST 'http://localhost:8545'
 * --data '{
 *    "jsonrpc":"2.0",
 *    "method":"rollup_captureConflation",
 *    "params":["296519", "296521"], "id":"1"
 *  }'
 * | jq '.result.capture' -r
 * | gzip > arithmetization/src/test/resources/replays/my-test-case.json.gz
 * }</pre>
 *
 * One can run this command: scripts/capture.pl --start xxx --end yyy --output my-test-case.json.gz
 */
@Slf4j
@ExtendWith(UnitTestWatcher.class)
public class ReplayTestTools {
  /** A list of block numbers used for testing purposes in {@link ReplayTests}. */
  static List<Arguments> BLOCK_NUMBERS = new ArrayList<>();

  /**
   * Loads a .json or .json.gz replay file generated by the {@link
   * net.consensys.linea.blockcapture.BlockCapturer} and execute it as a test.
   *
   * @param chainId Chain for testing (e.g. mainnet or sepolia, etc)
   * @param filename the file in resources/replays/ containing the replay
   * @param resultChecking enable checking of transaction results. This should always be enabled.
   *     However until existing problems are resolved with the replay mechanism, it may be useful to
   *     disable this for specific tests on a case-by-case basis.
   */
  public static void replay(BigInteger chainId, String filename, boolean resultChecking) {
    final InputStream fileStream =
        ReplayTestTools.class
            .getClassLoader()
            .getResourceAsStream("replays/%s".formatted(filename));
    if (fileStream == null) {
      fail("unable to find %s in replay resources".formatted(filename));
    }

    final InputStream stream;
    try {
      stream = filename.toLowerCase().endsWith("gz") ? new GZIPInputStream(fileStream) : fileStream;
    } catch (IOException e) {
      log.error("while loading {}: {}", filename, e.getMessage());
      throw new RuntimeException(e);
    }
    ReplayExecutionEnvironment.builder()
        .zkTracer(new ZkTracer(chainId))
        .txResultChecking(resultChecking)
        .build()
        .replay(chainId, new BufferedReader(new InputStreamReader(stream)));
  }

  /**
   * Implementation of replay for tests running on a given chain, with result checking enabled.
   *
   * @param chainId Chain for testing (e.g. mainnet or sepolia, etc)
   * @param filename Name of replay file
   */
  public static void replay(BigInteger chainId, String filename) {
    replay(chainId, filename, true);
  }

  /**
   * Run replay with the specific file path of a replay file. The conflated trace will be written to
   * the same directory as the replay file. Usage: replayAt("/path/to/your/star-end.json.gz");
   */
  public static void replayAt(BigInteger chainId, String filePath) {
    final Path path = Paths.get(filePath);
    final InputStream stream;
    try {
      stream = Files.newInputStream(path);
    } catch (IOException e) {
      log.error("while loading {}: {}", filePath, e.getMessage());
      throw new RuntimeException(e);
    }

    try (GZIPInputStream gzipStream = new GZIPInputStream(stream)) {
      ReplayExecutionEnvironment.builder()
          .zkTracer(new ZkTracer(chainId))
          .build()
          .replay(chainId, new BufferedReader(new InputStreamReader(gzipStream)), filePath);
    } catch (IOException e) {
      log.error("while processing {}: {}", filePath, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  public static void bulkReplay(BigInteger chainId, String directory) {
    Path dirPath = Paths.get(directory);
    Path conflatedDirPath = Paths.get(directory, "../conflated");
    Path replayedDirPath = Paths.get(directory, "../replayed");
    // Create conflated and replayed directories if they do not exist
    try {
      if (!Files.exists(conflatedDirPath)) {
        Files.createDirectory(conflatedDirPath);
      }
      if (!Files.exists(replayedDirPath)) {
        Files.createDirectory(replayedDirPath);
      }
    } catch (IOException e) {
      log.error(
          "Error creating directories {} or {}: {}",
          conflatedDirPath,
          replayedDirPath,
          e.getMessage());
      throw new RuntimeException(e);
    }

    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath, "*.json.gz")) {
      for (Path entry : stream) {
        files.add(entry);
      }
    } catch (IOException e) {
      log.error("Error reading directory {}: {}", directory, e.getMessage());
      throw new RuntimeException(e);
    }
    // Sort files before processing
    Collections.sort(files);
    for (Path entry : files) {
      String filePath = entry.toString();
      System.out.println("Replaying file: " + filePath);
      replayAt(chainId, filePath);
      // Move the .lt file to conflated directory with .tmp suffix
      Path ltFilePath = Paths.get(filePath.replace(".json.gz", ".lt"));
      if (Files.exists(ltFilePath)) {
        Path targetLtPath = conflatedDirPath.resolve(ltFilePath.getFileName().toString() + ".tmp");
        try {
          Files.move(ltFilePath, targetLtPath, StandardCopyOption.REPLACE_EXISTING);
          Path finalLtPath =
              targetLtPath.resolveSibling(
                  targetLtPath.getFileName().toString().replace(".tmp", ""));
          Files.move(targetLtPath, finalLtPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
          log.error("Error moving file {}: {}", ltFilePath, e.getMessage());
        }
      }

      // Move the .json.gz file to replayed directory
      Path targetJsonPath = replayedDirPath.resolve(entry.getFileName());

      try {
        Files.move(entry, targetJsonPath, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        log.error("Error moving file {}: {}", entry, e.getMessage());
      }
    }
  }

  /**
   * Adds a range of block numbers to the BLOCK_NUMBERS list.
   *
   * @param start the starting block number (inclusive)
   * @param end the ending block number (inclusive)
   */
  static void add(int start, int end) {
    for (int i = start; i <= end; i++) {
      BLOCK_NUMBERS.add(Arguments.of(i));
    }
  }

  /**
   * Adds a single block number to the BLOCK_NUMBERS list.
   *
   * @param start the block number to add
   */
  static void add(int start) {
    BLOCK_NUMBERS.add(Arguments.of(start));
  }
}
