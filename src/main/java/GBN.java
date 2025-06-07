import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GBN {
    //原始数据
    private String data;

    //segment大小固定为80
    private int mss = 80;
    //窗口大小固定为400
    private int windowSize = 400;

    /*窗口左右边界，实际为messageList的下标，与serverACK的变换规则如下：
    left=serverACK%80;
    */
    private int left;
    private int right;
    //超时时间初始值设定为300，后续动态更新
    private int timeoutTime=300;
    private InetAddress serverIP;
    private int serverPort;

    public GBN(String data,String serverIP,int serverPort){
        this.data = data;
        try {
            this.serverIP = InetAddress.getByName(serverIP);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        this.serverPort = serverPort;
    }

    public void start(){

        //数据分块
        List<String> dataList=new ArrayList<>();
        int tempTotalLength = data.length();
        while (tempTotalLength>0){

            if (tempTotalLength>=mss){
                String content=data.substring(0,mss);
                data=data.substring(mss);

                dataList.add(content);

                tempTotalLength-=mss;
            }else{
                String content=data.substring(0,tempTotalLength);
                data=data.substring(tempTotalLength);
                dataList.add(content);
                tempTotalLength=0;
            }
        }

        //建立连接
        int clientSeq = 0;
        int clientAck;
        byte[] receiveData = new byte[1024];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

        try {
            DatagramSocket socket = new DatagramSocket();
            System.out.println("UDP server 已启动");



            //第一次握手，发送SYN
            Message synMsg=new Message((short) 0,clientSeq,0);//这里SYN包的ackNUM没有意义，所以随便填为0
            byte synMsgBytes[]=synMsg.serialize();
            DatagramPacket synPacket=new DatagramPacket(synMsgBytes,synMsgBytes.length,serverIP,serverPort);
            socket.send(synPacket);
            System.out.println("发送第一次握手"+synMsg);

            //第二次握手，接收对面的SYN+ACK
            socket.receive(receivePacket);
            Message synAckMsg = Message.deserialize(receivePacket.getData());
            System.out.println("收到第二次握手: " + synAckMsg);

            if (synAckMsg.getType() != 2) {
                System.out.println("错误：预期SYN+ACK消息");
                return;
            }

            //第三次握手，发送ACK
            clientSeq++;//模拟TCP，SYN本身占一个序号。
            clientAck = synAckMsg.getSeqNum(); // 按照TCP：ACK应该是seq+1，但是为了和GBN的累计确认统一，ack=seq。且由于server不发送数据，后续将不变。
            Message ackMsg=new Message((short) 1,clientSeq,clientAck);
            byte ackMsgBytes[]=ackMsg.serialize();
            DatagramPacket ackPacket=new DatagramPacket(ackMsgBytes,ackMsgBytes.length,serverIP,serverPort);
            socket.send(ackPacket);
            System.out.println("发送第三次次握手"+ackMsg);

        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        //数据传输阶段
        System.out.println("连接建立成功，进入数据传输阶段");

        //开启监听线程

        //开启发送线程


    }
}
