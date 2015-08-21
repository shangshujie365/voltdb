/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.regressionsuites;

import java.io.IOException;

import org.voltdb.BackendTarget;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.compiler.VoltProjectBuilder;

public class TestQueryTimeout extends RegressionSuite {

    private static final int TIMEOUT = 1000;

    // DEBUG build of EE runs much slower, so the timing part is not deterministic.
    private static String ERRORMSG = "A SQL query was terminated after";

    ProcedureCallback m_callback = new ProcedureCallback() {
        @Override
        public void clientCallback(ClientResponse clientResponse)
                throws Exception {
            if (clientResponse.getStatus() != ClientResponse.SUCCESS) {
                throw new RuntimeException("Failed with response: " + clientResponse.getStatusString());
            }
        }
    };

    private void loadData(Client client, String tb, int scale)
            throws NoConnectionsException, IOException, ProcCallException {
        for (int i = 0; i < scale; i++) {
            client.callProcedure(m_callback, tb + ".insert", i, "MA", i % 6);
        }
        System.out.println("Finish loading " + scale + " rows for table " + tb);
    }

    private void truncateData(Client client, String tb)
             throws NoConnectionsException, IOException, ProcCallException {
        client.callProcedure("@AdHoc", "Truncate table " + tb);
        validateTableOfScalarLongs(client, "Select count(*) from " + tb, new long[]{0});
    }

    private void loadTables(Client client, int scaleP, int scaleR)
            throws IOException, ProcCallException, InterruptedException {
        loadData(client, "P1", scaleP);
        loadData(client, "R1", scaleR);
        client.drain();
    }

    private void truncateTables(Client client)
            throws IOException, ProcCallException, InterruptedException {
        truncateData(client, "P1");
        truncateData(client, "R1");
    }

    public void testReplicatedProcTimeout() throws IOException, ProcCallException, InterruptedException {
        if (isValgrind()) {
            // Disable the memcheck for this test, it takes too long
            return;
        }
        System.out.println("test replicated table procedures timeout...");

        Client client = this.getClient();
        loadTables(client, 0, 5000);

        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        //
        // Replicated table procedure tests
        //
        try {
            client.callProcedure("ReplicatedReadOnlyProc");
            fail();
        } catch(Exception ex) {
            assertTrue(ex.getMessage().contains(ERRORMSG));
        }

        // It's a write procedure and it's timed out safely because the MPI has not issue
        // any write query before it's timed out
        try {
            client.callProcedure("ReplicatedReadWriteProc");
            fail();
        } catch(Exception ex) {
            assertTrue(ex.getMessage().contains(ERRORMSG));
        }

        // It's a write procedure and should not be timed out.
        try {
            client.callProcedure("ReplicatedWriteReadProc");
        } catch(Exception ex) {
            fail("Write procedure should not be timed out");
        }
    }

    public void testPartitionedProcTimeout() throws IOException, ProcCallException, InterruptedException {
        if (isValgrind()) {
            // Disable the memcheck for this test, it takes too long
            return;
        }

        System.out.println("test partitioned table procedures timeout...");

        Client client = this.getClient();
        loadTables(client, 10000, 3000);

        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        //
        // Partition table procedure tests
        //
        try {
            client.callProcedure("PartitionReadOnlyProc");
            fail();
        } catch(Exception ex) {
            assertTrue(ex.getMessage().contains(ERRORMSG));
        }

        // Read on partition table should not have MPI optimizations
        // so the MPI should mark it write and not time out the procedure
        try {
            client.callProcedure("PartitionReadWriteProc");
        } catch(Exception ex) {
            fail("Write procedure should not be timed out");
        }

        // It's a write procedure and should not be timed out.
        try {
            client.callProcedure("PartitionWriteReadProc");
        } catch(Exception ex) {
            fail("Write procedure should not be timed out");
        }
    }

    private void checkTimeoutIncreasedProcSucceed(boolean sync, Client client, String procName, Object...params)
            throws IOException, ProcCallException, InterruptedException {
        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        try {
            client.callProcedure(procName, params);
            fail(procName + " is suppose to timed out, but not actually!");
        } catch(Exception ex) {
            assertTrue(ex.getMessage().contains(ERRORMSG));
        }

        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        // increase the individual timeout value in order to succeed running this long procedure
        try {
            if (sync) client.callProcedureWithTimeout(TIMEOUT*10, procName, params);
            else {
                client.callProcedureWithTimeout(m_callback, TIMEOUT*10, procName, params);
                client.drain();
            }
        } catch(Exception ex) {
            System.err.println(ex.getMessage());
            fail(procName + " is suppose to succeed!");
        }

        // check the global timeout value again
        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        // run the same procedure again to verify the global timeout value still applies
        try {
            client.callProcedure(procName, params);
            fail(procName + " is suppose to timed out, but not actually!");
        } catch(Exception ex) {
            assertTrue(ex.getMessage().contains(ERRORMSG));
        }

        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));
    }

    private void checkTimeoutDecreaseProcFailed(boolean sync, Client client, String procName, Object...params)
            throws IOException, ProcCallException, InterruptedException {
        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        try {
            client.callProcedure(procName, params);
        } catch(Exception ex) {
            fail(procName + " is suppose to succeed!");
        }

        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        // increase the individual timeout value in order to succeed running this long procedure
        try {
            if (sync) client.callProcedureWithTimeout(TIMEOUT / 500, procName, params);
            else {
                client.callProcedureWithTimeout(m_callback, TIMEOUT / 500, procName, params);
                client.drain();
            }
            fail(procName + " is suppose to timed out, but not actually!");
        } catch(Exception ex) {
            assertTrue(ex.getMessage().contains(ERRORMSG));
        }

        // check the global timeout value again
        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));

        // run the same procedure again to verify the global timeout value still applies
        try {
            client.callProcedure(procName, params);
        } catch(Exception ex) {
            fail(procName + " is suppose to succeed!");
        }

        checkDeploymentPropertyValue(client, "querytimeout", Integer.toString(TIMEOUT));
    }

    public void testIndividualProcTimeout() throws IOException, ProcCallException, InterruptedException {
        if (isValgrind()) {
            // Disable the memcheck for this test, it takes too long
            return;
        }
        Client client = this.getClient();
        boolean syncs[] = {true, false};
        for (boolean sync : syncs) {
            System.out.println("Testing " + (sync ? "synchronously": "asynchronously") + "  call");
            loadTables(client, 10000, 3000);
            checkTimeoutIncreasedProcSucceed(sync, client, "SPPartitionReadOnlyProc", 1);
            checkTimeoutIncreasedProcSucceed(sync, client, "PartitionReadOnlyProc");
            checkTimeoutIncreasedProcSucceed(sync, client, "ReplicatedReadOnlyProc");

            // truncate the data
            truncateTables(client);
            // load less data
            loadTables(client, 1000, 300);

            // MP asynchronously call seems to return immediately
            // Am I wrong? -xin
            if (sync) {
                checkTimeoutDecreaseProcFailed(sync, client, "SPPartitionReadOnlyProc", 1);
                checkTimeoutDecreaseProcFailed(sync, client, "PartitionReadOnlyProc");
                checkTimeoutDecreaseProcFailed(sync, client, "ReplicatedReadOnlyProc");
            }

            // truncate the data
            truncateTables(client);
        }
    }

    //
    // Suite builder boilerplate
    //

    public TestQueryTimeout(String name) {
        super(name);
    }
    static final Class<?>[] PROCEDURES = {
        org.voltdb_testprocs.regressionsuites.querytimeout.ReplicatedReadOnlyProc.class,
        org.voltdb_testprocs.regressionsuites.querytimeout.ReplicatedReadWriteProc.class,
        org.voltdb_testprocs.regressionsuites.querytimeout.ReplicatedWriteReadProc.class,
        org.voltdb_testprocs.regressionsuites.querytimeout.PartitionReadOnlyProc.class,
        org.voltdb_testprocs.regressionsuites.querytimeout.PartitionReadWriteProc.class,
        org.voltdb_testprocs.regressionsuites.querytimeout.PartitionWriteReadProc.class,
        org.voltdb_testprocs.regressionsuites.querytimeout.SPPartitionReadOnlyProc.class
    };

    static public junit.framework.Test suite() {
        VoltServerConfig config = null;
        MultiConfigSuiteBuilder builder = new MultiConfigSuiteBuilder(
                TestQueryTimeout.class);
        VoltProjectBuilder project = new VoltProjectBuilder();
        final String literalSchema =
                "CREATE TABLE R1 ( " +
                "phone_number INTEGER NOT NULL, " +
                "state VARCHAR(2) NOT NULL, " +
                "contestant_number INTEGER NOT NULL);" +

                "CREATE TABLE P1 ( " +
                "phone_number INTEGER NOT NULL, " +
                "state VARCHAR(2) NOT NULL, " +
                "contestant_number INTEGER NOT NULL);" +

                "PARTITION TABLE P1 ON COLUMN phone_number;" +
                ""
                ;
        try {
            project.addLiteralSchema(literalSchema);
        } catch (IOException e) {
            assertFalse(true);
        }
        project.addProcedures(PROCEDURES);

        project.setQueryTimeout(TIMEOUT);
        boolean success;

        config = new LocalCluster("querytimeout-onesite.jar", 1, 1, 0, BackendTarget.NATIVE_EE_JNI);
        success = config.compile(project);
        assertTrue(success);
        builder.addServerConfig(config);

        // Cluster
        config = new LocalCluster("querytimeout-cluster.jar", 2, 3, 1, BackendTarget.NATIVE_EE_JNI);
        success = config.compile(project);
        assertTrue(success);
        builder.addServerConfig(config);

        return builder;
    }
}
