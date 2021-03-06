/*
 * Copyright 2009 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.yammer.httptunnel.util;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.UpstreamMessageEvent;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import com.yammer.httptunnel.client.HttpTunnelClientChannelFactory;
import com.yammer.httptunnel.server.HttpTunnelServerChannelFactory;

/**
 * @author The Netty Project (netty-dev@lists.jboss.org)
 * @author Iain McGinniss (iain.mcginniss@onedrum.com)
 */
public class NettyTestUtils {

	private static Random random;

	static {
		random = new Random();
	}

	public static ByteBuffer convertReadable(ChannelBuffer b) {
		int startIndex = b.readerIndex();
		ByteBuffer converted = ByteBuffer.allocate(b.readableBytes());
		b.readBytes(converted);
		b.readerIndex(startIndex);
		converted.flip();
		return converted;
	}

	public static void assertEquals(ChannelBuffer expected, ChannelBuffer actual) {
		if (expected.readableBytes() != actual.readableBytes()) {
			Assert.failNotEquals("channel buffers have differing readable sizes", expected.readableBytes(), actual.readableBytes());
		}

		int startPositionExpected = expected.readerIndex();
		int startPositionActual = actual.readerIndex();
		int position = 0;
		while (expected.readable()) {
			byte expectedByte = expected.readByte();
			byte actualByte = actual.readByte();
			if (expectedByte != actualByte) {
				Assert.failNotEquals("channel buffers differ at position " + position, expectedByte, actualByte);
			}

			position++;
		}

		expected.readerIndex(startPositionExpected);
		actual.readerIndex(startPositionActual);
	}

	public static boolean checkEquals(ChannelBuffer expected, ChannelBuffer actual) {
		if (expected.readableBytes() != actual.readableBytes())
			return false;

		while (expected.readable()) {
			final byte expectedByte = expected.readByte();
			final byte actualByte = actual.readByte();
			if (expectedByte != actualByte)
				return false;

		}

		return true;
	}

	public static List<ChannelBuffer> splitIntoChunks(int chunkSize, ChannelBuffer... buffers) {
		LinkedList<ChannelBuffer> chunks = new LinkedList<ChannelBuffer>();

		ArrayList<ChannelBuffer> sourceBuffers = new ArrayList<ChannelBuffer>();
		Collections.addAll(sourceBuffers, buffers);
		Iterator<ChannelBuffer> sourceIter = sourceBuffers.iterator();
		ChannelBuffer chunk = ChannelBuffers.buffer(chunkSize);
		while (sourceIter.hasNext()) {
			ChannelBuffer source = sourceIter.next();

			int index = source.readerIndex();
			while (source.writerIndex() > index) {
				int fragmentSize = Math.min(source.writerIndex() - index, chunk.writableBytes());
				chunk.writeBytes(source, index, fragmentSize);
				if (!chunk.writable()) {
					chunks.add(chunk);
					chunk = ChannelBuffers.buffer(chunkSize);
				}
				index += fragmentSize;
			}
		}

		if (chunk.readable()) {
			chunks.add(chunk);
		}

		return chunks;
	}

	public static ChannelBuffer createData(long containedNumber) {
		final ChannelBuffer data = ChannelBuffers.dynamicBuffer();
		data.writeLong(containedNumber);

		return data;
	}

	public static ChannelBuffer createRandomData(int size) {
		final byte[] bytes = new byte[size];
		random.nextBytes(bytes);

		return ChannelBuffers.wrappedBuffer(bytes);
	}

	public static void checkIsUpstreamMessageEventContainingData(ChannelEvent event, ChannelBuffer expectedData) {
		final ChannelBuffer data = checkIsUpstreamMessageEvent(event, ChannelBuffer.class);
		assertEquals(expectedData, data);
	}

	public static <T> T checkIsUpstreamMessageEvent(ChannelEvent event, Class<T> expectedMessageType) {
		assertTrue(event instanceof UpstreamMessageEvent);
		UpstreamMessageEvent messageEvent = (UpstreamMessageEvent) event;
		assertTrue(expectedMessageType.isInstance(messageEvent.getMessage()));
		return expectedMessageType.cast(messageEvent.getMessage());
	}

	public static <T> T checkIsDownstreamMessageEvent(ChannelEvent event, Class<T> expectedMessageType) {
		assertTrue(event instanceof DownstreamMessageEvent);
		DownstreamMessageEvent messageEvent = (DownstreamMessageEvent) event;
		assertTrue(expectedMessageType.isInstance(messageEvent.getMessage()));
		return expectedMessageType.cast(messageEvent.getMessage());
	}

	public static InetSocketAddress createAddress(byte[] addr, int port) {
		try {
			return new InetSocketAddress(InetAddress.getByAddress(addr), port);
		}
		catch (UnknownHostException e) {
			throw new RuntimeException("Bad address in test");
		}
	}

	public static Throwable checkIsExceptionEvent(ChannelEvent ev) {
		assertTrue(ev instanceof ExceptionEvent);
		ExceptionEvent exceptionEv = (ExceptionEvent) ev;
		return exceptionEv.getCause();
	}

	public static ChannelStateEvent checkIsStateEvent(ChannelEvent event, ChannelState expectedState, Object expectedValue) {
		assertTrue(event instanceof ChannelStateEvent);
		ChannelStateEvent stateEvent = (ChannelStateEvent) event;
		Assert.assertEquals(expectedState, stateEvent.getState());
		Assert.assertEquals(expectedValue, stateEvent.getValue());
		return stateEvent;
	}

    public static InetAddress getLocalHost() throws UnknownHostException {
        return InetAddress.getLocalHost();
    }

	public static Channel createServerChannel(InetSocketAddress addr, ChannelPipelineFactory pipelineFactory) {
		// TCP socket factory
		ServerSocketChannelFactory socketFactory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());

		// HTTP socket factory
		socketFactory = new HttpTunnelServerChannelFactory(socketFactory);

		final ServerBootstrap bootstrap = new ServerBootstrap(socketFactory);
		bootstrap.setPipelineFactory(pipelineFactory);

		bootstrap.setOption("child.tcpNoDelay", true);
		bootstrap.setOption("reuseAddress", true);

		return bootstrap.bind(addr);
	}

	public static Channel createClientChannel(InetSocketAddress addr, ChannelPipelineFactory pipelineFactory, int timeout) {
		// TCP socket factory
		ClientSocketChannelFactory socketFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());

		// HTTP socket factory
		socketFactory = new HttpTunnelClientChannelFactory(socketFactory);

		final ClientBootstrap bootstrap = new ClientBootstrap(socketFactory);
		bootstrap.setPipelineFactory(pipelineFactory);

		bootstrap.setOption("tcpNoDelay", true);

		final ChannelFuture future = bootstrap.connect(addr);

		try {
			future.await(timeout, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		// If we managed to connect then set the channel and type
		if (future.isSuccess())
			return future.getChannel();

		// Otherwise cancel the attempt and give up
		future.cancel();
		return null;
	}
}
