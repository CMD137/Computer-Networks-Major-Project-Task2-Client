import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Message {
    //type:0:SYN。1：ACK.  2:SYN+ACK 3:PSH+ACK
    private short type;
    private int seqNum;
    private int ackNum;
    private String data;


    public Message() {
    }

    public Message(short type, int seqNum, int ackNum, String data) {
        this.type = type;
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.data = data;
    }

    public Message(short type, int seqNum, int ackNum) {
        this.type = type;
        this.seqNum = seqNum;
        this.ackNum = ackNum;
    }

    public short getType() {
        return type;
    }

    public void setType(short type) {
        this.type = type;
    }

    public int getSeqNum() {
        return seqNum;
    }

    public void setSeqNum(int seqNum) {
        this.seqNum = seqNum;
    }

    public int getAckNum() {
        return ackNum;
    }

    public void setAckNum(int ackNum) {
        this.ackNum = ackNum;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String toString() {
        return "Message{type = " + type + ", seqNum = " + seqNum + ", ackNum = " + ackNum + ", data = " + data + "}";
    }

    //序列化
    public byte[] serialize(){
        byte[] dataBytes = (data != null) ? data.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer byteBuffer=ByteBuffer.allocate(10+dataBytes.length);

        byteBuffer.putShort(type);
        byteBuffer.putInt(seqNum);
        byteBuffer.putInt(ackNum);
        byteBuffer.put(dataBytes);

        return byteBuffer.array();
    }

    //反序列化
    public static Message deserialize(byte[] bytes){
        ByteBuffer byteBuffer=ByteBuffer.wrap(bytes);

        short type=byteBuffer.getShort();
        int seqNum=byteBuffer.getInt();
        int ackNum=byteBuffer.getInt();

        if (type==3){
            byte[] dataBytes=new byte[bytes.length-10];
            byteBuffer.get(dataBytes);
            return new Message(type,seqNum,ackNum,new String(dataBytes,StandardCharsets.UTF_8));
        }

        return new Message(type,seqNum,ackNum);
    }
}
