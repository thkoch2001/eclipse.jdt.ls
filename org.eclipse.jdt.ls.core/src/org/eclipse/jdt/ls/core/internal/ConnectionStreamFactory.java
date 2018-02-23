/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channels;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.Platform;

/**
 * A factory for creating the streams for supported transmission methods.
 *
 * @author Gorkem Ercan
 *
 */
public class ConnectionStreamFactory {

	interface StreamProvider {
		InputStream getInputStream() throws IOException;

		OutputStream getOutputStream() throws IOException;
	}

	protected abstract class InetStreamProvider implements StreamProvider {
		protected final String host;
		protected final int port;
		protected InputStream fInputStream;
		protected OutputStream fOutputStream;
		protected AtomicBoolean initialized;

		public InetStreamProvider(String host, int port) {
			this.host = host;
			this.port = port;
			this.initialized = new AtomicBoolean(false);
		}

		abstract protected void setIOStreams() throws IOException;

		private void initializeConnection() throws IOException {
			setIOStreams();
			initialized.set(true);
		}

		@Override
		public synchronized InputStream getInputStream() throws IOException {
			if (!initialized.get()) {
				initializeConnection();
			}
			return fInputStream;
		}

		@Override
		public synchronized OutputStream getOutputStream() throws IOException {
			if (!initialized.get()) {
				initializeConnection();
			}
			return fOutputStream;
		}
	}

	protected final class SocketStreamProvider extends InetStreamProvider {

		public SocketStreamProvider(String host, int port) {
			super(host, port);
		}

		@Override
		protected void setIOStreams() throws IOException {
			Socket socket = new Socket(host, port);
			fInputStream = socket.getInputStream();
			fOutputStream = socket.getOutputStream();
		}
	}

	protected final class LanguageServerStreamProvider extends InetStreamProvider {

		public LanguageServerStreamProvider(String host, int port) {
			super(host, port);
		}

		@Override
		protected void setIOStreams() throws IOException {
			SocketAddress socketAddress = new InetSocketAddress(host, port);
			AsynchronousServerSocketChannel serverSocket = AsynchronousServerSocketChannel.open().bind(socketAddress);
			AsynchronousSocketChannel socketChannel;
			try {
				socketChannel = serverSocket.accept().get();
			} catch (InterruptedException | ExecutionException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
				return;
			}
			fInputStream = Channels.newInputStream(socketChannel);
			fOutputStream = Channels.newOutputStream(socketChannel);
		}
	}

	protected final class StdIOStreamProvider implements StreamProvider {

		/* (non-Javadoc)
		 * @see org.eclipse.jdt.ls.core.internal.ConnectionStreamFactory.StreamProvider#getInputStream()
		 */
		@Override
		public InputStream getInputStream() throws IOException {
			return JavaLanguageServerPlugin.getIn();
		}

		/* (non-Javadoc)
		 * @see org.eclipse.jdt.ls.core.internal.ConnectionStreamFactory.StreamProvider#getOutputStream()
		 */
		@Override
		public OutputStream getOutputStream() throws IOException {
			return JavaLanguageServerPlugin.getOut();
		}

	}

	private StreamProvider provider;

	/**
	 *
	 * @return
	 */
	public synchronized StreamProvider getSelectedStream() {
		if (provider == null) {
			provider = createProvider();
		}
		return provider;
	}

	private StreamProvider createProvider() {
		final Integer clientPort = JDTEnvironmentUtils.getClientPort();
		if (clientPort != null) {
			return new SocketStreamProvider(JDTEnvironmentUtils.getClientHost(), clientPort);
		}
		final Integer serverPort = JDTEnvironmentUtils.getServerPort();
		if (serverPort != null) {
			return new LanguageServerStreamProvider(JDTEnvironmentUtils.getClientHost(), serverPort);
		}
		return new StdIOStreamProvider();
	}

	public InputStream getInputStream() throws IOException {
		return getSelectedStream().getInputStream();
	}

	public OutputStream getOutputStream() throws IOException {
		return getSelectedStream().getOutputStream();
	}

	protected static boolean isWindows() {
		return Platform.OS_WIN32.equals(Platform.getOS());
	}

}
