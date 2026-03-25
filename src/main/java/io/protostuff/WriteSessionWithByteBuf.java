//========================================================================
//Copyright 2007-2010 David Yu dyuproject@gmail.com
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package io.protostuff;

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Designed to be subclassed by implementations of {@link Output} for easier serialization code for streaming or full
 * buffering. This is used when objects need to be serialzied/written into a {@code ByteBuf}.
 * 
 * @author David Yu
 * @created Sep 20, 2010
 */
public class WriteSessionWithByteBuf
{

    public interface FlushHandler
    {
        int flush(WriteSessionWithByteBuf session,
                byte[] buf, int offset, int len) throws IOException;

        int flush(WriteSessionWithByteBuf session,
                byte[] buf, int offset, int len,
                byte[] next, int nextoffset, int nextlen) throws IOException;

        int flush(WriteSessionWithByteBuf session,
                ByteBuf byteBuf,
                byte[] buf, int offset, int len) throws IOException;
    }

    /**
     * The main/root/head buffer of this write session.
     */
    public final ByteBuf byteBuf;

  
    /**
     * The actual number of bytes written to the buffer.
     */
    protected int size = 0;
    
    protected int writerIndex0 = 0;
    
    protected int readerIndex0 = 0;
    
    protected int streamIndex = 0;

    /**
     * The next buffer size used when growing the buffer.
     */
    public final int nextBufferSize;

    /**
     * The sink of this buffer.
     */
    public  OutputStream out;

    public final FlushHandler flushHandler;

    /**
     * The sink of this write session.
     */
    public final WriteSinkWithByteBuf sink;

    public WriteSessionWithByteBuf(ByteBuf out)
    {
        this(out, LinkedBuffer.DEFAULT_BUFFER_SIZE);
    }

    public WriteSessionWithByteBuf(ByteBuf out, int nextBufferSize)
    {
        this.byteBuf = out;
        this.nextBufferSize = nextBufferSize;
        //保存初始位置
        this.readerIndex0 = out.readerIndex();
        this.writerIndex0 = out.writerIndex();
        this.streamIndex = this.writerIndex0;
        out = null;
        flushHandler = null;

        sink = WriteSinkWithByteBuf.BUFFERED;
    }

    public WriteSessionWithByteBuf(ByteBuf out, OutputStream os, FlushHandler flushHandler,
            int nextBufferSize)
    {
        this.byteBuf = out;
        this.nextBufferSize = nextBufferSize;
        //保存初始位置
        this.readerIndex0 = out.readerIndex();
        this.writerIndex0 = out.writerIndex();
        this.streamIndex = this.writerIndex0;
        this.out = os;
        this.flushHandler = flushHandler;

        sink = WriteSinkWithByteBuf.STREAMED;

        assert out != null;
    }

    public WriteSessionWithByteBuf(ByteBuf out, OutputStream os)
    {
        this(out, os, null, LinkedBuffer.DEFAULT_BUFFER_SIZE);
    }
    
    protected void flushStream(){
        
        if(size- streamIndex >=8192){
            try {
                this.byteBuf.readBytes(out, 8192);
                streamIndex += 8192;
            } catch (IOException ex) {
                Logger.getLogger(WriteSessionWithByteBuf.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    /**
     * Resets this session for re-use. Meant to be overridden by subclasses that have other state to reset.
     */
    public void reset()
    {

    }

    /**
     * The buffer will be cleared (tail will point to the head) and the size will be reset to zero.
     * @return 
     */
    public WriteSessionWithByteBuf clear()
    {
        //tail = head.clear();
        this.byteBuf.writerIndex(this.writerIndex0);
        this.byteBuf.readerIndex(this.readerIndex0);
        size = 0;
        return this;
    }

    /**
     * Returns the amount of bytes written in this session.
     */
    public final int getSize()
    {
        return size;
    }

    /**
     * Returns a single byte array containg all the contents written to the buffer(s).
     * @return 
     */
    public final byte[] toByteArray()
    {
        final byte[] buf = new byte[size];
        this.byteBuf.getBytes(this.writerIndex0, buf);
        return buf;
    }

    protected int flush(byte[] buf, int offset, int len) throws IOException
    {
        if (flushHandler != null)
            return flushHandler.flush(this, buf, offset, len);

        out.write(buf, offset, len);
        return offset;
    }

    protected int flush(byte[] buf, int offset, int len,
            byte[] next, int nextoffset, int nextlen) throws IOException
    {
        if (flushHandler != null)
            return flushHandler.flush(this, buf, offset, len, next, nextoffset, nextlen);

        out.write(buf, offset, len);
        out.write(next, nextoffset, nextlen);
        return offset;
    }

    protected int flush(ByteBuf byteBuf,
            byte[] buf, int offset, int len) throws IOException
    {
        if (flushHandler != null)
            return flushHandler.flush(this, byteBuf, buf, offset, len);

        out.write(buf, offset, len);
        return byteBuf.readerIndex();
    }

}