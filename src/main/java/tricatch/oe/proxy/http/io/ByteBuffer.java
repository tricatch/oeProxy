package tricatch.oe.proxy.http.io;

public class ByteBuffer {

    private byte[] buffer;
    private int length;

    public ByteBuffer(int length) {
        this.buffer = new byte[length];
    }

    public ByteBuffer(byte[] buffer) {
        this.buffer = buffer;
        this.length = buffer.length;
    }

    public byte[] getBuffer() {
        return buffer;
    }

    public void setBuffer(byte[] buffer){
        this.buffer = buffer;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        if (length < 0 || length > buffer.length) {
            throw new IllegalArgumentException("Length must be between 0 and " + buffer.length + ", length=" + length);
        }
        this.length = length;
    }

    public byte get(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Length: " + length);
        }
        return buffer[index];
    }

    @Override
    public String toString() {
        return new String(buffer, 0, length);
    }

}
