/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.messaging;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.voltcore.messaging.HeartbeatMessage;
import org.voltcore.messaging.TransactionInfoBaseMessage;
import org.voltcore.network.NIOReadStream;
import org.voltcore.network.VoltProtocolHandler;
import org.voltcore.utils.HBBPool.SharedBBContainer;

public class CoalescedHeartbeatMessage extends TransactionInfoBaseMessage {

    private final long m_destinationHSIds[];
    private final long m_lastSafeTxnIds[];

    public CoalescedHeartbeatMessage() {
        m_destinationHSIds = null;
        m_lastSafeTxnIds = null;
    }

    /*
     * Used when deserializing to construct the messages to deliver locally
     */
    private HeartbeatMessage m_messages[];
    private long m_messageDestinations[];

    public CoalescedHeartbeatMessage(long initiatorHSId, long txnId,
                                     long destinationHSIds[],
                                     long lastSafeTxnIds[])
    {
        super(initiatorHSId, -1, txnId, txnId, true, false);
        m_destinationHSIds = destinationHSIds;
        m_lastSafeTxnIds = lastSafeTxnIds;
    }

    public HeartbeatMessage[] getHeartbeatsToDeliver() {
        return m_messages;
    }

    public long[] getHeartbeatDestinations() {
        return m_messageDestinations;
    }

    @Override
    public int getSerializedSize()
    {
        int msgsize = super.getSerializedSize();
        msgsize += 1 + // storage of count of heartbeat messages
            m_destinationHSIds.length * (8 + 8);  // Two longs per coalesced message
        return msgsize;
    }

    @Override
    protected void initFromBuffer(ByteBuffer buf) throws IOException {
        assert(false);
    }

    @Override
    public void initFromContainer(SharedBBContainer container) throws IOException {
        super.initFromContainer(container);
        ByteBuffer buf = container.b();
        final int numHeartbeats = buf.get();
        m_messages = new HeartbeatMessage[numHeartbeats];
        m_messageDestinations = new long[numHeartbeats];
        for (int ii = 0; ii < numHeartbeats; ii++) {
            m_messageDestinations[ii] = buf.getLong();
            m_messages[ii] = new HeartbeatMessage(super.m_initiatorHSId,
                                                  super.m_txnId,
                                                  buf.getLong());
            m_messages[ii].m_sourceHSId = m_sourceHSId;
        }
        assert(buf.limit() == buf.position());
        container.discard();
    }

    @Override
    public void initFromInputHandler(VoltProtocolHandler handler, NIOReadStream inputStream) throws IOException {
        initFromContainer(handler.getNextHBBMessage(inputStream));
    }

    @Override
    public void flattenToBuffer(ByteBuffer buf) throws IOException {
        buf.put(VoltDbMessageFactory.COALESCED_HEARTBEAT_ID);
        super.flattenToBuffer(buf);
        buf.put((byte)m_destinationHSIds.length);
        for (int ii = 0; ii < m_destinationHSIds.length; ii++) {
            buf.putLong(m_destinationHSIds[ii]);
            buf.putLong(m_lastSafeTxnIds[ii]);
        }
        assert(buf.limit() == buf.position());
        buf.limit(buf.position());
    }

    @Override
    public void implicitReference() {}

    @Override
    public void discard() {}
}
