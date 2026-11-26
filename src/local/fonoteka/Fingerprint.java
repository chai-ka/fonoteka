package local.fonoteka;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Spectral landmarks in four frequency bands, encoded as a Shazam-compatible signature. */
final class Fingerprint {
    private static final int N = 2048, HOP = 128, RATE = 16000;
    private static final int[] NEIGHBOURS = {-10,-7,-4,-3,1,2,5,8};
    private static final int[] FRAMES = {-53,-45,-91,-84,-77,-70,-63,-56,-42,-35,-28,-21,-14,-7};
    private final double[][] power = new double[128][1025], spread = new double[128][1025];
    private final double[] re = new double[N], im = new double[N], window = new double[N];
    private final ByteArrayOutputStream[] bands = new ByteArrayOutputStream[4];
    private final int[] previous = new int[4];
    Fingerprint() {
        for (int i=0;i<N;i++) window[i] = .5 - .5*Math.cos(2*Math.PI*(i+1)/(N+1));
        for (int i=0;i<4;i++) bands[i]=new ByteArrayOutputStream();
    }
    byte[] encode(byte[] wav) {
        ByteBuffer input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int rate = input.getInt(24);
        int length = (wav.length-44)/2;
        int samples = (int)((long)length*RATE/rate);
        short[] pcm = new short[samples+N];
        for (int i=0;i<samples;i++) {
            double pos = (double)i*rate/RATE;
            int a=(int)pos, b=Math.min(a+1,length-1);
            double fraction=pos-a;
            pcm[N-HOP+i]=(short)Math.round(input.getShort(44+2*a)*(1-fraction)+input.getShort(44+2*b)*fraction);
        }
        int frames=samples/HOP;
        for (int frame=0;frame<frames;frame++) {
            for (int k=0;k<N;k++) re[k]=pcm[frame*HOP+k]*window[k];
            Arrays.fill(im,0);
            fft();
            double[] p=power[frame&127], s=spread[frame&127];
            for (int k=0;k<p.length;k++) p[k]=(re[k]*re[k]+im[k]*im[k])/131072.0;
            for (int k=0;k<s.length;k++) {
                s[k]=k+2<s.length ? Math.max(p[k],Math.max(p[k+1],p[k+2])) : p[k];
                double v=s[k];
                for (int lag=1;lag<=6;lag+=lag==1?2:3) {
                    double[] prior=spread[(frame-lag)&127];
                    prior[k]=v=Math.max(v,prior[k]);
                }
            }
            if (frame>=46) peaks(frame+1);
        }
        ByteArrayOutputStream blocks=new ByteArrayOutputStream();
        for (int i=0;i<4;i++) {
            byte[] data=bands[i].toByteArray();
            if (data.length==0) continue;
            little(blocks,0x60030040+i,4);
            little(blocks,data.length,4);
            blocks.write(data,0,data.length);
            while ((blocks.size()&3)!=0) blocks.write(0);
        }
        byte[] body=blocks.toByteArray();
        ByteBuffer packet=ByteBuffer.allocate(56+body.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(0,0xcafe2580).putInt(8,body.length+8).putInt(12,0x94119c00);
        packet.putInt(28,3<<27).putInt(40,frames*HOP+3840).putInt(44,0x7c0000);
        packet.position(48);
        packet.putInt(0x40000000).putInt(body.length+8).put(body);
        CRC32 crc=new CRC32();
        crc.update(packet.array(),8,packet.capacity()-8);
        packet.putInt(4,(int)crc.getValue());
        return packet.array();
    }
    private void peaks(int n) {
        double[] p=power[(n-46)&127], near=spread[(n-49)&127];
        for (int k=10;k<1017;k++) {
            double v=p[k];
            if (v<1./64 || v<near[k]) continue;
            double max=0;
            for (int delta:NEIGHBOURS) max=Math.max(max,near[k+delta]);
            if (v<=max) continue;
            for (int delta:FRAMES) max=Math.max(max,spread[(n+delta)&127][k-1]);
            if (v<=max) continue;
            double center=magnitude(v), left=magnitude(p[k-1]), right=magnitude(p[k+1]);
            double curvature=2*center-left-right;
            if (curvature<=0) continue;
            int bin=(int)(64*k+32*(right-left)/curvature);
            double hz=bin*16000.0/(N*64);
            if (hz<250 || hz>5500) continue;
            int band=hz<520?0:hz<1450?1:hz<3500?2:3;
            int frame=n-46, gap=frame-previous[band];
            ByteArrayOutputStream out=bands[band];
            if (gap>=255) { out.write(255); little(out,frame,4); gap=0; }
            out.write(gap);
            little(out,(int)center,2);
            little(out,bin,2);
            previous[band]=frame;
        }
    }
    private static double magnitude(double p) { return 6144+1477.3*Math.log(Math.max(1./64,p)); }
    private static void little(ByteArrayOutputStream out,int val,int count) {
        for (int i=0;i<count;i++) out.write(val>>>(8*i));
    }
    private void fft() {
        for (int i=1,j=0;i<N;i++) {
            int bit=N>>1;
            while ((j&bit)!=0) { j^=bit; bit>>=1; }
            j^=bit;
            if (i<j) { double t=re[i]; re[i]=re[j]; re[j]=t; }
        }
        for (int width=2;width<=N;width*=2) {
            double angle=-2*Math.PI/width, cos=Math.cos(angle), sin=Math.sin(angle);
            for (int start=0;start<N;start+=width) {
                double x=1,y=0;
                for (int i=start;i<start+width/2;i++) {
                    int j=i+width/2;
                    double a=x*re[j]-y*im[j], b=x*im[j]+y*re[j];
                    re[j]=re[i]-a; im[j]=im[i]-b; re[i]+=a; im[i]+=b;
                    double nx=x*cos-y*sin; y=x*sin+y*cos; x=nx;
                }
            }
        }
    }
}
