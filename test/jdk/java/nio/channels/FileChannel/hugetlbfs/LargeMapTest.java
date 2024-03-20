/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

// import jdk.test.lib.RandomFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.*;

/*
 * @test
 * @bug 8286637
 * @summary Ensure that memory mapping beyond 32-bit range does not cause an
 *          EXCEPTION_ACCESS_VIOLATION.
 * @requires vm.bits == 64 & os.family == "linux"
 * @modules java.base/sun.nio.ch:+open
 * @library /test/lib
 * @build HugeTLBFSUtil
 * @compile --enable-preview -source ${jdk.version} LargeMapTest.java
 * @run main/othervm --enable-preview LargeMapTest
 * @key randomness
 */
public class LargeMapTest {

    /*
     * This needs to be mounted, e.g.:
     * - sudo hugeadm --create-global-mounts
     * - sudo mount -t hugetlbfs \
     * -o uid=$(id -u),gid=$(id-g),mode=0777,pagesize=2M \
     * none /var/lib/hugetlbfs/global/pagesize-2MB
     * 
     * Additionally, there must be enough free huge pages to map 4G, e.g.:
     * 
     * - sudo hugeadm --pool-pages-max 2M:2200
     * - sudo sysctl vm.nr_hugepages=2200
     */
    private static final Path BASE_DIR = Paths.get("/var/lib/hugetlbfs/global/pagesize-2MB");

    private static final long LENGTH = (1L << 32) + 512;
    private static final int EXTRA = 1024;
    private static final long BASE = LENGTH - EXTRA;
    static final long HUGE_PS = 2 * 1024 * 1024;
    private static final Random GEN = new Random();

    public static void main(String[] args) throws Throwable {
        System.out.println(System.getProperty("sun.arch.data.model"));
        System.out.println(System.getProperty("os.arch"));
        System.out.println(System.getProperty("java.version"));

        if (!Files.exists(BASE_DIR) || !Files.isDirectory(BASE_DIR)) {
            System.err.printf("hugetlbfs volume %s does not exist. Aborting test.%n", BASE_DIR);
            return;
        }

        if (!HugeTLBFSUtil.supports(HUGE_PS, 2010)) {
            return;
        }

        System.out.println("  Writing large file...");
        long t0 = System.nanoTime();
        Path p = createSparseTempFile("test", ".dat");

        if (!HugeTLBFSUtil.usesHugeBlocks(p.toFile(), HUGE_PS)) {
            System.err.printf("Unsupported test. %s does no use huge blocks%n", p.toFile());
            p.toFile().delete();
            return;
        }

        p.toFile().deleteOnExit();
        ByteBuffer bb;
        try (FileChannel fc = FileChannel.open(p, READ, WRITE)) {
            fc.position(BASE);
            byte[] b = new byte[EXTRA];
            GEN.nextBytes(b);
            bb = ByteBuffer.wrap(b);
            // fc.write(bb);
            try (var a = Arena.ofConfined()) {
                var tmp = fc.map(FileChannel.MapMode.READ_WRITE, fc.position(), bb.limit(), a);
                tmp.asByteBuffer().put(bb);
            }
            long t1 = System.nanoTime();
            System.out.printf("  Wrote large file in %d ns (%d ms) %n",
                    t1 - t0, TimeUnit.NANOSECONDS.toMillis(t1 - t0));
        }
        bb.rewind();

        try (FileChannel fc = FileChannel.open(p, READ, WRITE)) {
            System.out.printf("  Mapping [%d, %d) %n", 0, p.toFile().length());
            MemorySegment mappedMemorySegment = fc.map(FileChannel.MapMode.READ_WRITE, 0, p.toFile().length(),
                    Arena.ofAuto());
            MemorySegment target = mappedMemorySegment.asSlice(BASE, EXTRA);
            if (!target.asByteBuffer().equals(bb)) {
                throw new RuntimeException("Expected buffers to be equal");
            }
        }
    }

    public static Path createSparseTempFile(String prefix, String suffix) throws IOException {
        Path file = Files.createTempFile(BASE_DIR, prefix, suffix);
        Files.delete(file); // need CREATE_NEW to make the file sparse

        FileChannel fc = FileChannel.open(file, CREATE_NEW, SPARSE, WRITE);
        fc.close();
        return file;
    }
}
