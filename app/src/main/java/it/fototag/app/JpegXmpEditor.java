package it.fototag.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JPEG APP1 XMP editor, with Windows-compatible EXIF XPKeywords support. */
public final class JpegXmpEditor {
    private static final String HEADER="http://ns.adobe.com/xap/1.0/\0";
    private static final String EXTENDED="http://ns.adobe.com/xmp/extension/\0";
    private static final String EXIF="Exif\0\0";
    private static final String DC="http://purl.org/dc/elements/1.1/";
    private static final String RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String X="adobe:ns:meta/";
    private static final int XP_KEYWORDS=0x9C9E;
    private JpegXmpEditor() {}

    public static List<String> readKeywords(File input)throws Exception {
        try(InputStream in=new BufferedInputStream(new FileInputStream(input))){
            verify(in);
            while(true){
                int marker=marker(in);
                if(marker==0xDA||marker==0xD9)return Collections.emptyList();
                if(marker==0x01||(marker>=0xD0&&marker<=0xD7))continue;
                byte[] payload=segment(in);
                if(marker==0xE1&&startsWith(payload,HEADER)){
                    Document doc=parse(Arrays.copyOfRange(payload,HEADER.length(),payload.length));
                    return keywords(doc);
                }
            }
        }
    }

    /** Reads keywords from EXIF XPKeywords (the field Windows indexes as photo tags). */
    public static List<String> readXpKeywords(File input)throws Exception {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        try(InputStream in=new BufferedInputStream(new FileInputStream(input))){
            verify(in);
            while(true){
                int marker=marker(in);
                if(marker==0xDA||marker==0xD9)break;
                if(marker==0x01||(marker>=0xD0&&marker<=0xD7))continue;
                byte[] payload=segment(in);
                if(marker==0xE1&&startsWith(payload,EXIF))result.addAll(readXpKeywords(payload));
            }
        }
        return new ArrayList<>(result);
    }

    public static void writeKeywords(File input,File output,List<String> keywords)throws Exception{
        Document xmp=null;
        int exifSegments=0;
        try(InputStream in=new BufferedInputStream(new FileInputStream(input))){
            verify(in);
            while(true){
                int m=marker(in);
                if(m==0xDA||m==0xD9)break;
                if(m==0x01||(m>=0xD0&&m<=0xD7))continue;
                byte[] p=segment(in);
                if(m==0xE1&&startsWith(p,EXTENDED))throw new IOException("XMP esteso presente: modifica bloccata per non perdere metadati");
                if(m==0xE1&&startsWith(p,HEADER)){
                    if(xmp!=null)throw new IOException("Più pacchetti XMP: modifica bloccata");
                    xmp=parse(Arrays.copyOfRange(p,HEADER.length(),p.length));
                }
                if(m==0xE1&&startsWith(p,EXIF)&&++exifSegments>1)
                    throw new IOException("Più segmenti EXIF: modifica bloccata per sicurezza");
            }
        }
        byte[] encoded=serialize(update(xmp,keywords));
        byte[] header=HEADER.getBytes(StandardCharsets.US_ASCII);
        if(encoded.length+header.length+2>65535)throw new IOException("Troppi tag per un singolo segmento JPEG/XMP");
        byte[] xpPayload=null;
        if(exifSegments==0){
            xpPayload=createExif(keywords);
            if(xpPayload.length+2>65535)throw new IOException("Troppi tag per un singolo segmento JPEG/EXIF");
        }
        try(InputStream in=new BufferedInputStream(new FileInputStream(input));OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
            verify(in);out.write(0xFF);out.write(0xD8);
            boolean xmpInserted=false,exifInserted=false;
            while(true){
                int m=marker(in);
                if(m==0xDA){
                    if(!xmpInserted){writeXmp(out,header,encoded);xmpInserted=true;}
                    if(!exifInserted){if(xpPayload==null)xpPayload=createExif(keywords);writeSegment(out,0xE1,xpPayload);exifInserted=true;}
                    int length=read16(in);
                    out.write(0xFF);out.write(m);write16(out,length);
                    copyExactly(in,out,length-2);
                    copy(in,out);
                    break;
                }
                if(m==0xD9){
                    if(!xmpInserted){writeXmp(out,header,encoded);xmpInserted=true;}
                    if(!exifInserted){if(xpPayload==null)xpPayload=createExif(keywords);writeSegment(out,0xE1,xpPayload);exifInserted=true;}
                    out.write(0xFF);out.write(0xD9);copy(in,out);break;
                }
                if(m==0x01||(m>=0xD0&&m<=0xD7)){out.write(0xFF);out.write(m);continue;}
                byte[] p=segment(in);
                if(m==0xE1&&startsWith(p,HEADER)){
                    if(!xmpInserted){writeXmp(out,header,encoded);xmpInserted=true;}
                }else if(m==0xE1&&startsWith(p,EXIF)){
                    byte[] changed=writeXpKeywords(p,keywords);
                    if(changed.length+2>65535)throw new IOException("Troppi tag per un singolo segmento JPEG/EXIF");
                    writeSegment(out,m,changed);exifInserted=true;
                }else{
                    out.write(0xFF);out.write(m);write16(out,p.length+2);out.write(p);
                }
            }
        }
    }

    private static void writeXmp(OutputStream out,byte[] header,byte[] packet)throws IOException{
        out.write(0xFF);out.write(0xE1);write16(out,header.length+packet.length+2);
        out.write(header);out.write(packet);
    }
    private static void writeSegment(OutputStream out,int marker,byte[] payload)throws IOException{
        out.write(0xFF);out.write(marker);write16(out,payload.length+2);out.write(payload);
    }

    private static Document update(Document d,List<String> tags)throws Exception{
        if(d==null){
            d=factory().newDocumentBuilder().newDocument();
            Element root=d.createElementNS(X,"x:xmpmeta");d.appendChild(root);
            Element rdf=d.createElementNS(RDF,"rdf:RDF");root.appendChild(rdf);
            rdf.appendChild(d.createElementNS(RDF,"rdf:Description"));
        }
        NodeList subjects=d.getElementsByTagNameNS(DC,"subject");
        Element target=null;
        if(subjects.getLength()>0){target=(Element)subjects.item(0);while(target.hasChildNodes())target.removeChild(target.getFirstChild());}
        else{
            NodeList descriptions=d.getElementsByTagNameNS(RDF,"Description");
            if(descriptions.getLength()==0){
                NodeList rdfNodes=d.getElementsByTagNameNS(RDF,"RDF");
                if(rdfNodes.getLength()==0)throw new IOException("Struttura XMP non riconosciuta");
                Element description=d.createElementNS(RDF,"rdf:Description");rdfNodes.item(0).appendChild(description);
                descriptions=d.getElementsByTagNameNS(RDF,"Description");
            }
            target=d.createElementNS(DC,"dc:subject");descriptions.item(0).appendChild(target);
        }
        Element bag=d.createElementNS(RDF,"rdf:Bag");target.appendChild(bag);
        for(String t:cleanTags(tags)){Element li=d.createElementNS(RDF,"rdf:li");li.appendChild(d.createTextNode(t));bag.appendChild(li);}
        return d;
    }
    private static List<String> keywords(Document d){
        LinkedHashSet<String> found=new LinkedHashSet<>();
        NodeList subjects=d.getElementsByTagNameNS(DC,"subject");
        for(int i=0;i<subjects.getLength();i++){
            NodeList lis=((Element)subjects.item(i)).getElementsByTagNameNS(RDF,"li");
            for(int j=0;j<lis.getLength();j++){String v=lis.item(j).getTextContent().trim();if(!v.isEmpty())found.add(v);}
        }
        return new ArrayList<>(found);
    }

    private static List<String> cleanTags(List<String> tags){
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        if(tags!=null)for(String tag:tags)if(tag!=null){String s=tag.trim();if(!s.isEmpty())unique.add(s);}
        return new ArrayList<>(unique);
    }
    private static byte[] encodeXpKeywords(List<String> tags){
        String value=String.join(";",cleanTags(tags));
        return (value+'\0').getBytes(StandardCharsets.UTF_16LE);
    }
    private static List<String> readXpKeywords(byte[] payload)throws IOException{
        Tiff t=Tiff.open(payload);
        long ifdOffset=t.u32(4);
        if(ifdOffset==0)return Collections.emptyList();
        int ifd=t.checkedIndex(ifdOffset,2);
        int count=t.u16(ifd);
        t.checkedIndex(ifd+2L+12L*count,4);
        for(int i=0;i<count;i++){
            int entry=ifd+2+i*12;
            if(t.u16(entry)==XP_KEYWORDS){
                int type=t.u16(entry+2);long n=t.u32(entry+4);
                if(type!=1||n>Integer.MAX_VALUE)throw new IOException("Campo EXIF XPKeywords non valido");
                int data=(n<=4)?entry+8:t.checkedIndex(t.u32(entry+8),n);
                if(n>4)t.checkedIndex(t.u32(entry+8),n);
                byte[] raw=Arrays.copyOfRange(t.bytes,data,data+(int)n);
                int end=raw.length-(raw.length%2);
                for(int j=0;j+1<end;j+=2)if(raw[j]==0&&raw[j+1]==0){end=j;break;}
                String value=new String(raw,0,end,StandardCharsets.UTF_16LE);
                LinkedHashSet<String> found=new LinkedHashSet<>();
                for(String part:value.split("[;\\u0000]+")){String s=part.trim();if(!s.isEmpty())found.add(s);}
                return new ArrayList<>(found);
            }
        }
        return Collections.emptyList();
    }

    private static byte[] writeXpKeywords(byte[] payload,List<String> tags)throws IOException{
        Tiff t=Tiff.open(payload);byte[] value=encodeXpKeywords(tags);
        byte[] tiff=Arrays.copyOfRange(payload,6,payload.length);
        long ifdLong=t.u32(4);
        if(ifdLong==0){
            byte[] generated=createExif(tags);
            return generated;
        }
        int ifd=t.checkedIndex(ifdLong,2),count=t.u16(ifd);
        int end=ifd+2+count*12;
        t.checkedIndex(end,4);
        int found=-1;
        for(int i=0;i<count;i++)if(t.u16(ifd+2+i*12)==XP_KEYWORDS){found=ifd+2+i*12;break;}
        ByteArrayOutputStream appended=new ByteArrayOutputStream();
        if(found>=0){
            if(value.length>4){
                if((long)tiff.length+value.length>0xFFFFFFFFL)throw new IOException("EXIF troppo grande");
                int offset=tiff.length;appended.write(value);
                t.put32(tiff,found+4,value.length);t.put32(tiff,found+8,offset);
            }else{
                t.put32(tiff,found+4,value.length);Arrays.fill(tiff,found+8,found+12,(byte)0);
                System.arraycopy(value,0,tiff,found+8,value.length);
            }
        }else{
            if(count==65535)throw new IOException("Troppe voci nel segmento EXIF");
            if((tiff.length&1)!=0){appended.write(0);}
            int newIfd=tiff.length+appended.size();
            byte[] newTable=new byte[2+(count+1)*12+4];
            t.put16(newTable,0,count+1);
            System.arraycopy(tiff,ifd+2,newTable,2,count*12);
            int newEntry=2+count*12;
            t.put16(newTable,newEntry,XP_KEYWORDS);t.put16(newTable,newEntry+2,1);
            t.put32(newTable,newEntry+4,value.length);
            if(value.length<=4){System.arraycopy(value,0,newTable,newEntry+8,value.length);}
            else{
                long valueOffset=(long)newIfd+newTable.length;
                if(valueOffset+value.length>0xFFFFFFFFL)throw new IOException("EXIF troppo grande");
                t.put32(newTable,newEntry+8,(int)valueOffset);appended.write(value);
            }
            System.arraycopy(tiff, end, newTable, 2+(count+1)*12, 4);
            appended.write(newTable);
            t.put32(tiff,4,newIfd);
        }
        byte[] result=Arrays.copyOf(payload,6+tiff.length+appended.size());
        System.arraycopy(tiff,0,result,6,tiff.length);
        byte[] extra=appended.toByteArray();System.arraycopy(extra,0,result,6+tiff.length,extra.length);
        return result;
    }

    private static byte[] createExif(List<String> tags)throws IOException{
        byte[] value=encodeXpKeywords(tags);
        byte[] payload=new byte[6+26+value.length];
        byte[] signature=EXIF.getBytes(StandardCharsets.US_ASCII);System.arraycopy(signature,0,payload,0,signature.length);
        int base=6;payload[base]='I';payload[base+1]='I';put16le(payload,base+2,42);put32le(payload,base+4,8);
        put16le(payload,base+8,1);put16le(payload,base+10,XP_KEYWORDS);put16le(payload,base+12,1);
        put32le(payload,base+14,value.length);
        if(value.length<=4)System.arraycopy(value,0,payload,base+18,value.length);
        else put32le(payload,base+18,26);
        put32le(payload,base+22,0);System.arraycopy(value,0,payload,base+26,value.length);
        return payload;
    }

    private static final class Tiff{
        final byte[] bytes;final boolean little;
        private Tiff(byte[] bytes,boolean little){this.bytes=bytes;this.little=little;}
        static Tiff open(byte[] payload)throws IOException{
            if(!startsWith(payload,EXIF)||payload.length<14)throw new IOException("Segmento EXIF non valido");
            boolean little=payload[6]=='I'&&payload[7]=='I';
            if(!little&&!(payload[6]=='M'&&payload[7]=='M'))throw new IOException("Byte order EXIF non valido");
            Tiff t=new Tiff(Arrays.copyOfRange(payload,6,payload.length),little);
            if(t.u16(2)!=42)throw new IOException("Header TIFF/EXIF non valido");
            return t;
        }
        int u16(int p)throws IOException{checkedIndex(p,2);int a=bytes[p]&255,b=bytes[p+1]&255;return little?(a|b<<8):(a<<8|b);}
        long u32(int p)throws IOException{checkedIndex(p,4);long a=bytes[p]&255L,b=bytes[p+1]&255L,c=bytes[p+2]&255L,d=bytes[p+3]&255L;return little?(a|b<<8|c<<16|d<<24):(a<<24|b<<16|c<<8|d);}
        int checkedIndex(long p,long size)throws IOException{if(p<0||size<0||p+size>bytes.length||p+size<p||p>Integer.MAX_VALUE)throw new IOException("Offset EXIF fuori dai limiti");return (int)p;}
        void put16(byte[] a,int p,int v){if(little){a[p]=(byte)v;a[p+1]=(byte)(v>>>8);}else{a[p]=(byte)(v>>>8);a[p+1]=(byte)v;}}
        void put32(byte[] a,int p,long v){if(little){for(int i=0;i<4;i++)a[p+i]=(byte)(v>>>(8*i));}else{for(int i=0;i<4;i++)a[p+i]=(byte)(v>>>(8*(3-i)));}}
    }

    private static byte[] serialize(Document d)throws Exception{
        TransformerFactory tf=TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        Transformer t=tf.newTransformer();t.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");
        ByteArrayOutputStream out=new ByteArrayOutputStream();t.transform(new DOMSource(d),new StreamResult(out));return out.toByteArray();
    }
    private static Document parse(byte[] b)throws Exception{
        int n=b.length;
        while(n>0&&(b[n-1]==0||b[n-1]==' '||b[n-1]=='\n'||b[n-1]=='\r'))n--;
        String xml = new String(b,0,n,StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        if(xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))throw new IOException("DTD/entità XML non consentite nei metadati XMP");
        return factory().newDocumentBuilder().parse(new ByteArrayInputStream(b,0,n));
    }
    private static DocumentBuilderFactory factory()throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true); }catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities",false); }catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities",false); }catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false); }catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setXIncludeAware(false); } catch(UnsupportedOperationException ignored) {}
        f.setExpandEntityReferences(false);return f;
    }
    private static boolean startsWith(byte[] b,String s){byte[] p=s.getBytes(StandardCharsets.US_ASCII);if(b.length<p.length)return false;for(int i=0;i<p.length;i++)if(b[i]!=p[i])return false;return true;}
    private static void verify(InputStream in)throws IOException{if(in.read()!=0xFF||in.read()!=0xD8)throw new IOException("Non è un JPEG valido");}
    private static int marker(InputStream in)throws IOException{int x;do{x=in.read();if(x==-1)throw new EOFException("JPEG incompleto");}while(x!=0xFF);do{x=in.read();if(x==-1)throw new EOFException("JPEG incompleto");}while(x==0xFF);if(x==0x00)throw new IOException("Marker JPEG non valido");return x;}
    private static byte[] segment(InputStream in)throws IOException{int len=read16(in);if(len<2)throw new IOException("Segmento JPEG non valido");byte[] b=new byte[len-2];int pos=0;while(pos<b.length){int n=in.read(b,pos,b.length-pos);if(n<0)throw new EOFException();pos+=n;}return b;}
    private static int read16(InputStream in)throws IOException{int hi=in.read(),lo=in.read();if(hi<0||lo<0)throw new EOFException();return hi*256+lo;}
    private static void write16(OutputStream o,int v)throws IOException{o.write((v>>>8)&255);o.write(v&255);}
    private static void put16le(byte[] a,int p,int v){a[p]=(byte)v;a[p+1]=(byte)(v>>>8);}
    private static void put32le(byte[] a,int p,long v){for(int i=0;i<4;i++)a[p+i]=(byte)(v>>>(8*i));}
    private static void copyExactly(InputStream in,OutputStream out,int n)throws IOException{byte[] b=new byte[8192];while(n>0){int x=in.read(b,0,Math.min(n,b.length));if(x<0)throw new EOFException();out.write(b,0,x);n-=x;}}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
}
