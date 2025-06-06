public class GBN {
    //原始数据
    private String data;

    //segment大小固定为80
    private int mss = 80;
    //窗口大小固定为400
    private int windowSize = 400;
    //窗口左右边界
    private int left;
    private int right;
    //超时时间初始值设定为300，后续动态更新
    private int timeoutTime=300;
    private String serverIP;
    private int serverPort;

    public GBN(String data,String serverIP,int serverPort){
        this.data = data;
        this.serverIP = serverIP;
        this.serverPort = serverPort;
    }

    public  void start(){
        //数据分块

        //建立连接

        //第一次握手

        //第二次握手

        //第三次握手

        //数据传输阶段

        //开启发送线程

        //开启监听线程
    }
}
