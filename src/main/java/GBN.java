import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class GBN {
    //原始数据
    private String data;
    private List<String> dataList;

    //连接相关变量：
    private InetAddress serverIP;
    private int serverPort;
    private DatagramSocket socket;

    private int clientSeq = 0;
    private int clientAck;

    //滑动窗口相关变量：
    //segment大小固定为80
    private int mss = 80;
    //窗口大小固定为400
    private int windowSize = 400;
    //窗口左右边界，实际为messageList的下标
    private int left=0;
    private int right=0;

    //控制重发相关信号
    //锁对象，控制left, right，acked[],sendTimes
    private final Object lock = new Object();
    //记录已经确认过的消息，下标同dataList
    boolean[] acked;
    //记录窗口内信息发送时间，下标同acked
    private List<Long> sendTimes;

    //动态超时时间相关变量
    //初始超时时间:
    private int estimatedRTT=100;
    //超时时间
    private int timeoutInterval =estimatedRTT;
    //偏差，初始设置为estimatedRTT/2
    private int devRTT=estimatedRTT/2;
    //两个参数
    private double alphaRtt=0.125;
    private double betaRtt=0.125;
    /*
    EstimatedRTT = (1 - α) * EstimatedRTT + α * SampleRTT
    DevRTT = (1 - β) * DevRTT + β * |SampleRTT - EstimatedRTT|
    TimeoutInterval = EstimatedRTT + 4 * DevRTT
    */


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

                //test
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

            acked=new boolean[dataList.size()];

            //开启监听线程
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true){
                        try {
                            socket.receive(receivePacket);
                            Message ackMsg = Message.deserialize(receivePacket.getData());

                            int ackSeq = ackMsg.getAckNum(); //累计确认
                            int nextIndex = (ackSeq + mss - 1) / mss;//向上取整，最后面有个4，不然left没往前移动

                            //test
                            //System.out.println("收到:\n"+ackMsg+"\t此时:ackSeq="+ackSeq+"\t nextIndex="+nextIndex);
                            long now = System.currentTimeMillis();
                            synchronized (lock) {

                                //每收到一个ACK，动态计算timeoutInterval
                                int sampleRtt= (int) (now-sendTimes.get(nextIndex - 1));
                                estimatedRTT = (int) ((1 - alphaRtt) * estimatedRTT + alphaRtt * sampleRtt);
                                devRTT = (int) ((1 - betaRtt) * devRTT + betaRtt * Math.abs(sampleRtt - estimatedRTT));
                                timeoutInterval=estimatedRTT + 4 * devRTT;

                                //temp
                                System.out.println("timeoutInterval更新为："+timeoutInterval);

                                System.out.println("第"+(nextIndex)+"个包已收到：第"+(ackSeq-dataList.get(nextIndex-1).length()+1)+"到第"+ackSeq+"个字节，RTT："+ sampleRtt +"ms");

                                //注意这里是i < ackIndex而非<=,我debug了半天
                                for (int i = left; i < nextIndex && i < acked.length; i++) {
                                    acked[i] = true;
                                }
                                left = nextIndex; // 滑动窗口左边界右移
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
                    if (!acked[left]&&(now-sendTimes.get(left))> timeoutInterval){
                        //test
                        //System.out.println("第"+left+"条信息触发超时重传：left:"+left+"right:"+right);

                        for (int i = left; i < right; i++) {
                            System.out.print("重传:");
                            sendSegment(i);
                            sendTimes.set(i, now); //重传，刷新记录的时间
                            //temp
                            //System.out.println("设置第"+i+"个信息的时间："+now);
                        }
                    }

                    // 发送新包（窗口未满）
                    while (right - left < windowSize/mss && right < dataList.size()) {
                        sendSegment(right);
                        sendTimes.set(right,System.currentTimeMillis());
                        //temp
                        //System.out.println("设置第"+right+"个信息的时间："+System.currentTimeMillis());
                        right++;
                    }
                }

                Thread.sleep(10);
            }


            System.out.println("传输阶段结束");

            //test
            //System.out.println("left="+left+"\tright="+right);

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
        System.out.println("发送出第"+(index+1)+"个包：第"+clientSeq+"到第"+(clientSeq+msg.getData().length()-1)+"个字节");
    }

}
