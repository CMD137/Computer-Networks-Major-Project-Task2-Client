import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GBN {
    //原始数据
    private String data;

    //segment大小固定为80
    private int mss = 80;
    //窗口大小固定为400
    private int windowSize = 400;

    /*窗口左右边界，实际为messageList的下标，与serverACK的变换规则如下：
    收到的下标序号=serverACK%80;
    */
    private int left=0;
    private int right=0;
    //超时时间初始值设定为300，后续动态更新
    private int timeoutTime=300;
    private InetAddress serverIP;
    private int serverPort;

    //锁对象，控制left, right，acked[]。
    private final Object lock = new Object();

    private int clientSeq = 0;
    private int clientAck;

    private DatagramSocket socket;

    private List<String> dataList;

    //记录已经确认过的消息，下标同dataList
    boolean[] acked;

    //记录窗口内信息发送时间，下标同acked
    private List<Long> sendTimes;

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
        dataList=new ArrayList<>();
        int tempTotalLength = data.length();

        int temp=0;
        while (tempTotalLength>0){

            if (tempTotalLength>=mss){
                String content=data.substring(0,mss);
                data=data.substring(mss);
                dataList.add(content);
                tempTotalLength-=mss;

                //System.out.println(temp+"::::::"+content);
            }else{
                String content=data.substring(0,tempTotalLength);
                data=data.substring(tempTotalLength);
                dataList.add(content);
                tempTotalLength=0;
                //System.out.println(temp+"::::::"+content);
            }
            temp++;
        }

        //建立连接

        byte[] receiveData = new byte[1024];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

        try {
            socket = new DatagramSocket();
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

            //数据传输阶段
            System.out.println("连接建立成功，进入数据传输阶段");
            //开启监听线程

            acked=new boolean[dataList.size()];
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true){
                        try {
                            socket.receive(receivePacket);
                            Message ackMsg = Message.deserialize(receivePacket.getData());

                            int ackSeq = ackMsg.getAckNum(); //累计确认
                            //向上取整，最后面有个4，不然left没往前移动
                            int ackIndex = (ackSeq + mss - 1) / mss;

                            System.out.println("收到:\n"+ackMsg+"\t此时:ackSeq="+ackSeq+"\tackIndex="+ackIndex);

                            synchronized (lock) {
                                //注意这里是i < ackIndex而非<=,我debug了半天我草
                                for (int i = left; i < ackIndex && i < acked.length; i++) {
                                    acked[i] = true;
                                }
                                left = ackIndex; // 滑动窗口左边界右移
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }).start();

            //开始发送（主线程即为发送线程）

            sendTimes=new ArrayList<>(dataList.size());
            //初始化下
            for (int i = 0; i < dataList.size(); i++) {
                sendTimes.add(0L);
            }


            while (left < dataList.size()) {

                /*
                //检查acked数组
                System.out.println("/////////////////////////////////////////////////////////////////////////////////////////////////");
                System.out.println("此时:left:"+left+"\tright:"+right);
                for (int i = 0; i < dataList.size(); i++) {
                    System.out.println("acked["+i+"]="+acked[i]);
                }
                System.out.println("/////////////////////////////////////////////////////////////////////////////////////////////////");
                */

                synchronized (lock) {
                    long now = System.currentTimeMillis();
                    // 处理超时重传
                    if (!acked[left]&&(now-sendTimes.get(left))>timeoutTime){
                        System.out.println("第"+left+"条信息触发超时重传：left:"+left+"right:"+right);

                        for (int i = left; i < right; i++) {
                            sendSegment(i);
                            sendTimes.set(i, now); //重传，刷新记录的时间
                        }
                    }

                    // 发送新包（窗口未满）
                    while (right - left < 5 && right < dataList.size()) {
                        sendSegment(right);
                        sendTimes.set(right,System.currentTimeMillis());
                        right++;
                    }
                }

                Thread.sleep(10);
            }

            System.out.println("传输阶段结束！left="+left+"\tright="+right);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendSegment(int index) throws IOException {
        clientSeq=index*80+1;
        Message msg = new Message((short) 3, clientSeq, 0,dataList.get(index));
        byte[] msgBytes = msg.serialize();
        DatagramPacket packet = new DatagramPacket(msgBytes, msgBytes.length, serverIP, serverPort);
        socket.send(packet);
        System.out.println("发送"+msg);
    }

}
