package net.tomp2p.p2p.builder;

import java.io.IOException;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

import net.tomp2p.connection.PeerConnection;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureChannelCreator;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.RequestHandlerTCP;
import net.tomp2p.utils.Utils;

public class SendDirectBuilder
{
	final private Peer peer;
	private PeerAddress remotePeer;
	private ChannelBuffer requestBuffer;
	private PeerConnection connection;
	private Object object;
	private FutureChannelCreator futureChannelCreator;
	public SendDirectBuilder(Peer peer)
	{
		this.peer = peer;
	}
	
	public PeerAddress getRemotePeer()
	{
		return remotePeer;
	}

	public SendDirectBuilder setRemotePeer(PeerAddress remotePeer)
	{
		this.remotePeer = remotePeer;
		return this;
	}

	public ChannelBuffer getRequestBuffer()
	{
		return requestBuffer;
	}

	public SendDirectBuilder setRequestBuffer(ChannelBuffer requestBuffer)
	{
		this.requestBuffer = requestBuffer;
		return this;
	}

	public PeerConnection getConnection()
	{
		return connection;
	}

	public SendDirectBuilder setConnection(PeerConnection connection)
	{
		this.connection = connection;
		return this;
	}

	public Object getObject()
	{
		return object;
	}

	public SendDirectBuilder setObject(Object object)
	{
		this.object = object;
		return this;
	}
	
	public FutureChannelCreator getFutureChannelCreator()
	{
		return futureChannelCreator;
	}

	public SendDirectBuilder setFutureChannelCreator(FutureChannelCreator futureChannelCreator)
	{
		this.futureChannelCreator = futureChannelCreator;
		return this;
	}
	
	public FutureResponse build()
	{
		final boolean keepAlive;
		if(remotePeer != null && connection == null)
		{
			keepAlive = false;
		}
		else if(remotePeer == null && connection != null)
		{
			keepAlive = true;
		}
		else
		{
			throw new IllegalArgumentException("either remotePeer or connection has to be set");
		}
		final boolean raw;
		if(object != null && requestBuffer == null)
		{
			byte[] me;
			try
			{
				me = Utils.encodeJavaObject(object);
			}
			catch (IOException e)
			{
				FutureResponse futureResponse = new FutureResponse(null);
				return futureResponse.setFailed("cannot serialize object: "+e);
			}
			requestBuffer = ChannelBuffers.wrappedBuffer(me);
			raw = false;
		}
		else
		{
			raw = true;
		}
		if(requestBuffer != null)
		{
			if(keepAlive)
			{
				return sendDirectAlive(raw);
			}
			else
			{
				if(futureChannelCreator == null)
				{
					futureChannelCreator = peer.getConnectionBean().getConnectionReservation().reserve(1, "send-direct-builder");
				}
				return sendDirectClose(raw);
			}
		}
		else
		{
			throw new IllegalArgumentException("either object or requestBuffer has to be set");
		}
	}

	private FutureResponse sendDirectAlive(boolean raw)
	{
		RequestHandlerTCP<FutureResponse> request = peer.getDirectDataRPC().prepareSend(connection.getDestination(),
				requestBuffer.slice(), raw);
		request.setKeepAlive(true);
		// since we keep one connection open, we need to make sure that we do
		// not send anything in parallel.
		try
		{
			connection.aquireSingleConnection();
		}
		catch (InterruptedException e)
		{
			request.getFutureResponse().setFailed("Interupted " + e);
		}
		request.sendTCP(connection.getChannelCreator(), connection.getIdleTCPMillis());
		request.getFutureResponse().addListener(new BaseFutureAdapter<FutureResponse>()
		{
			@Override
			public void operationComplete(FutureResponse future) throws Exception
			{
				connection.releaseSingleConnection();
			}
		});
		return request.getFutureResponse();
	}
	
	private FutureResponse sendDirectClose(final boolean raw)
	{
		final RequestHandlerTCP<FutureResponse> request = peer.getDirectDataRPC().prepareSend(remotePeer, requestBuffer.slice(),
				raw);
		futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>()
		{
			@Override
			public void operationComplete(FutureChannelCreator future) throws Exception
			{
				if(future.isSuccess())
				{
					FutureResponse futureResponse = request.sendTCP(future.getChannelCreator());
					Utils.addReleaseListenerAll(futureResponse, peer.getConnectionBean().getConnectionReservation(), future.getChannelCreator());
				}
				else
				{
					request.getFutureResponse().setFailed(future);
				}
			}
		});
		return request.getFutureResponse();
	}

	
}