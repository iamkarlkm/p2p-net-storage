
import java.util.Random;



/**
 * java raid6算法测试
 * @author Administrator
 */
public class RaidTest {

    //此表的本原多项式为0X11D
    static int[] log = new int[256];
    static int[] alog = new int[256];
    static int log255;

    public static void generate_Galois_table() {
        alog[0] = 1;
        int i;
        int alog_data;
        for (i = 1; i < 256; i++) {
            alog_data = alog[i - 1] * 2;
            if (alog_data >= 256) {
                alog_data ^= 285;
            }
            alog[i] = alog_data;
            log[alog[i]] = i;
        }
        log255 = log[255];
    }

    public static final int Galois_mutipile(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return alog[(log[a] ^ log[b]) % 255];//need % 255?
    }

    public static final int Galois_division(int a, int b) {
        if (a == b) {
            return 0;
        }
        if (b == 0) {
            return a;
        }
        return alog[((log[a] ^ log[b]) + 255) % 255];
    }
    
    public static final int Galois_mutipile255(int a) {
        if (a == 0) {
            return 0;
        }
        return alog[(log[a] ^ log255) % 255];//need % 255?
    }

    public static final int Galois_division255(int a) {
        if (a == 255) {
            return 0;
        }
        return alog[((log[a] ^ log255) + 255) % 255];
    }
    public static void main(String[] args) {
        
    
        generate_Galois_table();
        int i;
        //输出正向逆向表
        int j = 0;
        for (i = 0; i < 256; i++) {
            System.out.printf("%02x ", alog[i]);
            j++;
            if (j == 16) {
                System.out.printf("\n");
                j = 0;
            }
        }
        System.out.printf("\n\n\n");
        j = 0;
        for (i = 0; i < 256; i++) {
            System.out.printf("%02x ", log[i]);
            j++;
            if (j == 16) {
                System.out.printf("\n");
                j = 0;
            }
        }
        System.out.printf("\n\n");
        //printf("%d\n",Galois_mutipile(129,5));
        //printf("%d\n",Galois_division(97,5));

        //RAID6 测试:
        int[] D = new int[10];
        //int[] K = new int[10];
        int P = 0, Q = 0;
        //srand(time(NULL));
        for (i = 0; i < 5; i++) {
            D[i] = i + 202;
            //K[i]= 255;//1=<K[i]<=255
            System.out.printf("D[%d]=%d\n", i, D[i]);
            //System.out.printf("K[%d]=%d\n", i, K[i]);
            P ^= D[i];
           // Q ^= Galois_mutipile(D[i], K[i]);
            Q ^= Galois_mutipile255(D[i]);
        }
        System.out.printf("P=%d Q=%d\n", P, Q);

        //令D1 P丢失，赋0
        D[0] = 0;
        P = 0;
        System.out.printf("数据丢失!\n");
        //还原D1
        for (i = 1; i <= 4; i++) {
            //D[0] ^= Galois_mutipile(D[i], K[i]);
            D[0] ^= Galois_mutipile255(D[i]);
        }
        D[0] ^= Q;
        //D[0] = Galois_division(D[0], K[0]);
        D[0] = Galois_division255(D[0]);
        for (i = 0; i < 5; i++) {
            P ^= D[i];
        }
        System.out.printf("恢复:\nP=%d D[0]=%d", P, D[0]);

        //return 0;
    }

}
