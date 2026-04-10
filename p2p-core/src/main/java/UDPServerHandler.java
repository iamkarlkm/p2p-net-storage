/*
 * Copyright 2022 learn-netty4 Project
 *
 * The learn-netty4 Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

/**
 * UDP 服务端处理器：接收 DatagramPacket 并返回应答包。
 */
@Slf4j
public class UDPServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final Random random = new Random();

    // 广播消息
    private static final String[] quotes = {
        "鹅鹅鹅",
        "曲项向天歌",
        "白毛浮绿水",
        "红掌拨清波",
    };

    private static String nextQuote() {
        int quoteId;
        synchronized (random) {
            quoteId = random.nextInt(quotes.length);
        }
        return quotes[quoteId];
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
        P2PWrapper request = SerializationUtil.deserializeWrapper(P2PWrapper.class, msg.content());
        System.out.println("request:"+request);
        ByteBuf buffer = SerializationUtil.serializeToByteBuf(P2PWrapper.build(request.getSeq(),P2PCommand.ECHO, "server ack"), P2PWrapperEncoder.MAGIC);
        System.out.println("writeAndFlush msg.sender()() -> "+(InetSocketAddress) msg.sender());
        ctx.writeAndFlush(new DatagramPacket(buffer, (InetSocketAddress) msg.sender())).sync();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 异常处理
        log.error("出现异常",cause);
    }
}
