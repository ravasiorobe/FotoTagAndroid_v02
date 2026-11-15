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
            // JPEG minimo sintetico con APP0, commento, scan e dati sintetici.
            byte[] jpeg = {(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xe0,0,4,0x11,0x22,
              (byte)0xff,(byte)0xfe,0,5,0x33,0x44,0x55,
              (byte)0xff,(byte)0xda,0,8,1,1,0,0,0x3f,0,(byte)0x90,(byte)0xab,(byte)0xff,0,(byte)0xff,(byte)0xd9};
            Files.write(original.toPath(),jpeg);
            File out=temp.resolve("tagged.jpg").toFile();
            List<String> tags=new ArrayList<>();
            for(int i=0;i<200;i++) tags.add("Tag "+i);
            tags.add("Città");tags.add("<montagna>");tags.add("Tag 2");
            JpegXmpEditor.writeKeywords(original,out,tags);
            List<String> read=JpegXmpEditor.readKeywords(out);
            check(read.size()==202,"Tag deduplicati: "+read.size());
            check(read.contains("Città") && read.contains("<montagna>"),"Unicode/XML escape");
            check(!Arrays.equals(Files.readAllBytes(original.toPath()),Files.readAllBytes(out.toPath())),"XMP non scritto");
            byte[] written=Files.readAllBytes(out.toPath());
            check(written[0]==(byte)0xff && written[1]==(byte)0xd8,"Header JPEG");
            check(written[written.length-2]==(byte)0xff && written[written.length-1]==(byte)0xd9,"JPEG trailer");
            File out2=temp.resolve("updated.jpg").toFile();
            JpegXmpEditor.writeKeywords(out,out2,Arrays.asList("Mare","Moto"));
            check(JpegXmpEditor.readKeywords(out2).equals(Arrays.asList("Mare","Moto")),"Sostituzione tag");
            File invalid=temp.resolve("invalid.jpg").toFile();
            Files.write(invalid.toPath(),new byte[]{1,2,3,4});
            boolean rejected=false;
            try{JpegXmpEditor.readKeywords(invalid);}catch(IOException expected){rejected=true;}
            check(rejected,"JPEG invalido non rifiutato");
            System.out.println("OK: scrittura, lettura, 200+ tag, Unicode, XML, modifica, JPEG invalido");
        }finally{
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)){
                paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});
            }
        }
    }
}
