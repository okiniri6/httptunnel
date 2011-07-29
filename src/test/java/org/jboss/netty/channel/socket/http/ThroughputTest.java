package org.jboss.netty.channel.socket.http;

import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.WriteCompletionEvent;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.http.client.HttpTunnelClientChannelFactory;
import org.jboss.netty.channel.socket.http.server.HttpTunnelServerChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.junit.Ignore;
import org.junit.Test;

public class ThroughputTest {

	public static final int DATA_AMOUNT = 1024; // 1Gb
	public static final int TIMEOUT = 2;

	private Channel createServerChannel(InetSocketAddress addr, ChannelPipelineFactory pipelineFactory) {
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

	private Channel createClientChannel(InetSocketAddress addr, ChannelPipelineFactory pipelineFactory) {
		// TCP socket factory
		ClientSocketChannelFactory socketFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());

		// HTTP socket factory
		socketFactory = new HttpTunnelClientChannelFactory(socketFactory);

		final ClientBootstrap bootstrap = new ClientBootstrap(socketFactory);
		bootstrap.setPipelineFactory(pipelineFactory);

		bootstrap.setOption("tcpNoDelay", true);

		final ChannelFuture future = bootstrap.connect(addr);

		try { future.await(TIMEOUT, TimeUnit.SECONDS); } catch (InterruptedException e) { }

		// If we managed to connect then set the channel and type
		if (future.isSuccess())
			return future.getChannel();

		// Otherwise cancel the attempt and give up
		future.cancel();
		return null;
	}

	// Ignored because really doing this over the loopback interface means nothing
	// This needs converted to run over multiple machines rather than as a unit test
	@Test @Ignore
	public void testThroughput() {
		final InetSocketAddress addr = new InetSocketAddress("localhost", 8888);

		final ThroughputChannelHandler serverHandler = new ThroughputChannelHandler();
		final Channel server = this.createServerChannel(addr, new ChannelPipelineFactory() {
			@Override
			public ChannelPipeline getPipeline() throws Exception {
				return Channels.pipeline(serverHandler);
			}
		});

		final ThroughputChannelHandler clientHandler = new ThroughputChannelHandler();
		final Channel client = this.createClientChannel(addr, new ChannelPipelineFactory() {
			@Override
			public ChannelPipeline getPipeline() throws Exception {
				return Channels.pipeline(clientHandler);
			}
		});

		assertTrue("no client channel", clientHandler.channel != null);
		assertTrue("no accepted channel", serverHandler.channel != null);

		final ChannelBuffer buffer = ChannelBuffers.buffer(1024 * 128); // 128kb chunks
		buffer.writeZero(buffer.capacity());

		final int loop = DATA_AMOUNT * 8 / 2;
		final long startTime = System.currentTimeMillis();

		// Send client to server
		for (int i = 0;i < loop;i++) {
			buffer.resetReaderIndex();
			clientHandler.channel.write(buffer).awaitUninterruptibly();
		}

		// Send server to client
		for (int i = 0;i < loop;i++) {
			buffer.resetReaderIndex();
			serverHandler.channel.write(buffer).awaitUninterruptibly();
		}

		final long duration = (System.currentTimeMillis() - startTime) / 1000;
		final long transferred = serverHandler.read + clientHandler.read;

		System.out.println(String.format("Transferred:\t%s", FileUtils.byteCountToDisplaySize(transferred)));
		System.out.println(String.format("Duration:\t%s secs", duration));
		System.out.println(String.format("Speed:\t\t%s/sec", FileUtils.byteCountToDisplaySize(transferred / duration)));

		client.close().awaitUninterruptibly(TIMEOUT, TimeUnit.SECONDS);
		server.close().awaitUninterruptibly(TIMEOUT, TimeUnit.SECONDS);
	}

	private class ThroughputChannelHandler extends SimpleChannelHandler {

		private Channel channel;
		private long read;
		private long write;

		public ThroughputChannelHandler() {
			read = 0;
			write = 0;
		}

		@Override
		public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) {
			this.channel = ctx.getChannel();
		}

		@Override
		public void writeComplete(ChannelHandlerContext ctx, WriteCompletionEvent e) {
			write += e.getWrittenAmount();
		}

		@Override
		public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
			read += ((ChannelBuffer) e.getMessage()).capacity();
		}
	}
}
