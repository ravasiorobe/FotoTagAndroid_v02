import it.fototag.app.JpegXmpEditor;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class JpegXmpEditorTest {
    static void check(boolean ok, String msg) { if(!ok) throw new AssertionError(msg); }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("fototag_test_");
        try {
            File original=temp.resolve("original.jpg").toFile();
            byte[] jpeg={(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xe0,0,4,0x11,0x22,
              (byte)0xff,(byte)0xfe,0,5,0x33,0x44,0x55,
              (byte)0xff,(byte)0xda,0,8,1,1,0,0,0x3f,0,(byte)0x90,(byte)0xab,(byte)0xff,0,(byte)0xff,(byte)0xd9};
            Files.write(original.toPath(),jpeg);
            File out=temp.resolve("tagged.jpg").toFile();
            List<String> tags=new ArrayList<>();for(int i=0;i<200;i++) tags.add("Tag "+i);
            tags.add("Città");tags.add("<montagna>");tags.add("Tag 2");
            JpegXmpEditor.writeKeywords(original,out,tags);
            List<String> read=JpegXmpEditor.readKeywords(out);
            check(read.size()==202,"Tag XMP deduplicati: "+read.size());
            check(read.contains("Città") && read.contains("<montagna>"),"Unicode/XML escape");
            check(JpegXmpEditor.readXpKeywords(out).equals(read),"Tag EXIF XPKeywords non coerenti");
            check(!Arrays.equals(Files.readAllBytes(original.toPath()),Files.readAllBytes(out.toPath())),"XMP/EXIF non scritto");
            byte[] written=Files.readAllBytes(out.toPath());
            check(written[0]==(byte)0xff && written[1]==(byte)0xd8,"Header JPEG");
            check(written[written.length-2]==(byte)0xff && written[written.length-1]==(byte)0xd9,"JPEG trailer");
            File out2=temp.resolve("updated.jpg").toFile();
            JpegXmpEditor.writeKeywords(out,out2,Arrays.asList("Mare","Moto"));
            check(JpegXmpEditor.readKeywords(out2).equals(Arrays.asList("Mare","Moto")),"Sostituzione XMP");
            check(JpegXmpEditor.readXpKeywords(out2).equals(Arrays.asList("Mare","Moto")),"Sostituzione XPKeywords");

            // Real EXIF IFD0 fixture with an Orientation tag: adding XPKeywords must retain it.
            File exifInput=temp.resolve("exif.jpg").toFile();Files.write(exifInput,jpegWithOrientation());
            File exifOutput=temp.resolve("exif-tagged.jpg").toFile();
            JpegXmpEditor.writeKeywords(exifInput,exifOutput,Arrays.asList("Windows", "Prova"));
            check(JpegXmpEditor.readXpKeywords(exifOutput).equals(Arrays.asList("Windows", "Prova")),"XPKeywords su EXIF esistente");
            check(hasOrientation(exifOutput),"La modifica ha perso Orientation EXIF");

            File invalid=temp.resolve("invalid.jpg").toFile();Files.write(invalid.toPath(),new byte[]{1,2,3,4});
            boolean rejected=false;try{JpegXmpEditor.readKeywords(invalid);}catch(IOException expected){rejected=true;}
            check(rejected,"JPEG invalido non rifiutato");
            System.out.println("OK: XMP, XPKeywords, Unicode, EXIF preservato, JPEG invalido");
        }finally{
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}
        }
    }
    private static byte[] jpegWithOrientation(){
        // APP1 Exif: little-endian TIFF, IFD0 with one SHORT Orientation=1 entry.
        byte[] exif=new byte[6+26];byte[] signature={'E','x','i','f',0,0};System.arraycopy(signature,0,exif,0,6);
        int b=6;exif[b]='I';exif[b+1]='I';put16(exif,b+2,42);put32(exif,b+4,8);put16(exif,b+8,1);
        put16(exif,b+10,0x0112);put16(exif,b+12,3);put32(exif,b+14,1);put16(exif,b+18,1);put32(exif,b+22,0);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(0xff);out.write(0xd8);out.write(0xff);out.write(0xe1);
        int length=exif.length+2;out.write(length>>>8);out.write(length);out.write(exif,0,exif.length);
        byte[] scan={(byte)0xff,(byte)0xda,0,8,1,1,0,0,0x3f,0,(byte)0x90,(byte)0xff,(byte)0xd9};out.write(scan,0,scan.length);return out.toByteArray();
    }
    private static boolean hasOrientation(File file)throws IOException{
        byte[] b=Files.readAllBytes(file.toPath());
        for(int i=2;i+4<b.length;){if((b[i]&255)!=255)break;int marker=b[i+1]&255;int len=((b[i+2]&255)<<8)|(b[i+3]&255);if(marker==0xe1&&i+4+len-2<=b.length&&b[i+4]=='E'&&b[i+5]=='x'&&b[i+6]=='i'&&b[i+7]=='f'){
            int t=i+10;long off=(b[t+4]&255L)|((b[t+5]&255L)<<8)|((b[t+6]&255L)<<16)|((b[t+7]&255L)<<24);int ifd=t+(int)off;int count=(b[ifd]&255)|((b[ifd+1]&255)<<8);for(int e=0;e<count;e++){int p=ifd+2+e*12;if(((b[p]&255)|((b[p+1]&255)<<8))==0x0112)return true;}return false;
        }if(marker==0xda)break;i+=2+len;}
        return false;
    }
    private static void put16(byte[] a,int p,int v){a[p]=(byte)v;a[p+1]=(byte)(v>>>8);}
    private static void put32(byte[] a,int p,long v){for(int i=0;i<4;i++)a[p+i]=(byte)(v>>>(8*i));}
}
