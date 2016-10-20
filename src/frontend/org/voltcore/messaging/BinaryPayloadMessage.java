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
package org.voltcore.messaging;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.voltcore.network.NIOReadStream;
import org.voltcore.network.VoltProtocolHandler;
import org.voltcore.utils.HBBPool.SharedBBContainer;

public class BinaryPayloadMessage extends VoltMessage {

    public byte m_payload[];
    public byte m_metadata[];

    public BinaryPayloadMessage() {}
    public BinaryPayloadMessage( byte metadata[], byte payload[]) {
        m_payload = payload;
        m_metadata = metadata;
        if (metadata == null || metadata.length > Short.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
    }

    @Override
    protected void initFromBuffer(ByteBuffer buf) throws IOException {
        m_metadata = new byte[buf.getShort()];
        buf.get(m_metadata);
        final int payloadLength = buf.getInt();
        if (payloadLength > -1) {
            m_payload = new byte[payloadLength];
            buf.get(m_payload);
        }
        assert(buf.limit() == buf.position());
    }

    @Override
    public void initFromContainer(SharedBBContainer container) {
        assert(false);
    }

    @Override
    public void initFromInputHandler(VoltProtocolHandler handler, NIOReadStream inputStream) throws IOException {
        initFromBuffer(handler.getNextBBMessage(inputStream));
    }

    @Override
    public int getSerializedSize() {
        int msgsize = m_metadata.length + 6 + super.getSerializedSize();
        if (m_payload != null) {
            msgsize += m_payload.length;
        }
        return msgsize;
    }

    @Override
    public void flattenToBuffer(ByteBuffer buf) {
        buf.put(VoltMessageFactory.BINARY_PAYLOAD_ID);
        buf.putShort((short)m_metadata.length);
        buf.put(m_metadata);
        if (m_payload == null) {
            buf.putInt(-1);
        } else {
            buf.putInt(m_payload.length);
            buf.put(m_payload);
        }

        assert(buf.limit() == buf.position());
        buf.limit(buf.position());
    }

    @Override
    public void implicitReference() {}

    @Override
    public void discard() {}
}
