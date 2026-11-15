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

/** Basic JPEG APP1 XMP editor: preserves JPEG scan data, EXIF and unrelated segments. */
public final class JpegXmpEditor {
    private static final String HEADER="http://ns.adobe.com/xap/1.0/\0";
    private static final String EXTENDED="http://ns.adobe.com/xmp/extension/\0";
    private static final String DC="http://purl.org/dc/elements/1.1/";
    private static final String RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String X="adobe:ns:meta/";
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
    public static void writeKeywords(File input,File output,List<String> keywords)throws Exception{
        // First pass: capture existing XMP to preserve other properties.
        Document xmp=null;
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
            }
        }
        byte[] encoded=serialize(update(xmp,keywords));
        byte[] header=HEADER.getBytes(StandardCharsets.US_ASCII);
        if(encoded.length+header.length+2>65535)throw new IOException("Troppi tag per un singolo segmento JPEG/XMP");
        try(InputStream in=new BufferedInputStream(new FileInputStream(input));OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
            verify(in);out.write(0xFF);out.write(0xD8);
            boolean inserted=false;
            while(true){
                int m=marker(in);
                if(m==0xDA){
                    if(!inserted){writeXmp(out,header,encoded);inserted=true;}
                    int length=read16(in);
                    out.write(0xFF);out.write(m);write16(out,length);
                    copyExactly(in,out,length-2);
                    copy(in,out); // entropy-coded image: unchanged byte for byte
                    break;
                }
                if(m==0xD9){
                    if(!inserted)writeXmp(out,header,encoded);
                    out.write(0xFF);out.write(0xD9);copy(in,out);break;
                }
                if(m==0x01||(m>=0xD0&&m<=0xD7)){out.write(0xFF);out.write(m);continue;}
                byte[] p=segment(in);
                if(m==0xE1&&startsWith(p,HEADER)){
                    if(!inserted){writeXmp(out,header,encoded);inserted=true;}
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
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(String tag:tags){String s=tag.trim();if(!s.isEmpty())unique.add(s);}
        for(String t:unique){Element li=d.createElementNS(RDF,"rdf:li");li.appendChild(d.createTextNode(t));bag.appendChild(li);}
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
        if(xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
            throw new IOException("DTD/entità XML non consentite nei metadati XMP");
        return factory().newDocumentBuilder().parse(new ByteArrayInputStream(b,0,n));
    }
    private static DocumentBuilderFactory factory()throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // Alcuni parser Android non espongono tutte le feature Xerces.
        // La validazione esplicita sopra rifiuta DTD ed entità; le opzioni
        // disponibili vengono comunque applicate per difesa aggiuntiva.
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true); }
        catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities",false); }
        catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities",false); }
        catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false); }
        catch(javax.xml.parsers.ParserConfigurationException ignored) {}
        try { f.setXIncludeAware(false); } catch(UnsupportedOperationException ignored) {}
        f.setExpandEntityReferences(false);
        return f;
    }
    private static boolean startsWith(byte[] b,String s){byte[] p=s.getBytes(StandardCharsets.US_ASCII);
        if(b.length<p.length)return false;for(int i=0;i<p.length;i++)if(b[i]!=p[i])return false;return true;}
    private static void verify(InputStream in)throws IOException{if(in.read()!=0xFF||in.read()!=0xD8)throw new IOException("Non è un JPEG valido");}
    private static int marker(InputStream in)throws IOException{
        int x;do{x=in.read();if(x==-1)throw new EOFException("JPEG incompleto");}while(x!=0xFF);
        do{x=in.read();if(x==-1)throw new EOFException("JPEG incompleto");}while(x==0xFF);
        if(x==0x00)throw new IOException("Marker JPEG non valido");return x;
    }
    private static byte[] segment(InputStream in)throws IOException{
        int len=read16(in);if(len<2)throw new IOException("Segmento JPEG non valido");
        byte[] b=new byte[len-2];int pos=0;
        while(pos<b.length){int n=in.read(b,pos,b.length-pos);if(n<0)throw new EOFException();pos+=n;}return b;
    }
    private static int read16(InputStream in)throws IOException{int hi=in.read(),lo=in.read();if(hi<0||lo<0)throw new EOFException();return hi*256+lo;}
    private static void write16(OutputStream o,int v)throws IOException{o.write((v>>>8)&255);o.write(v&255);}
    private static void copyExactly(InputStream in,OutputStream out,int n)throws IOException{
        byte[] b=new byte[8192];while(n>0){int x=in.read(b,0,Math.min(n,b.length));if(x<0)throw new EOFException();out.write(b,0,x);n-=x;}
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
}
