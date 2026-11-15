package it.fototag.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

/** FotoTag: selezione multipla e libreria persistente di parole chiave. */
public final class MainActivity extends Activity {
    private static final int PICK_PHOTOS=101;
    private static final String PREFS="fototag_tags_v2", KEY="library";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> photos=new ArrayList<>();
    private final LinkedHashSet<String> library=new LinkedHashSet<>();
    private final LinkedHashSet<String> chosen=new LinkedHashSet<>();
    private TextView status, selectedCount, tagCount;
    private EditText newTags, search;
    private LinearLayout tagList;
    private Button saveButton, selectButton;
    private boolean busy=false;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        loadLibrary();
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(18),px(20),px(18),px(30));
        scroll.addView(root);
        root.addView(label("FotoTag",30,true));
        root.addView(label("Assegna tag a più fotografie JPEG in una sola operazione",15,false));
        selectButton=button("1. Seleziona fotografie (anche molte insieme)"); root.addView(selectButton);
        selectButton.setOnClickListener(v->choosePhotos());
        selectedCount=label("Nessuna fotografia selezionata",16,true);root.addView(selectedCount);
        root.addView(label("2. Seleziona i tag già creati",18,true));
        search=new EditText(this);search.setSingleLine(true);search.setHint("Cerca tra i tag salvati");root.addView(search);
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){refreshLibrary();}
            public void afterTextChanged(android.text.Editable e){}
        });
        tagCount=label("",14,false);root.addView(tagCount);
        tagList=new LinearLayout(this);tagList.setOrientation(LinearLayout.VERTICAL);root.addView(tagList);
        root.addView(label("3. Crea nuovi tag",18,true));
        newTags=new EditText(this);newTags.setHint("Esempio: Vacanze, Montagna, 2026");
        newTags.setMinLines(2);newTags.setMaxLines(4);root.addView(newTags);
        Button add=button("Crea e seleziona nuovi tag");root.addView(add);
        add.setOnClickListener(v->{
            String[] parts=newTags.getText().toString().split("[,;\\n]+");
            for(String part:parts){String tag=part.trim();if(tag.length()>0 && tag.length()<=120){
                // Riusa la forma originale del tag per evitare duplicati con maiuscole diverse.
                String existing=findExisting(tag);
                String real=existing==null?tag:existing;
                library.add(real);chosen.add(real);
            }}
            persistLibrary();newTags.setText("");refreshLibrary();
        });
        saveButton=button("4. Applica tag alle foto selezionate");root.addView(saveButton);
        saveButton.setEnabled(false);saveButton.setOnClickListener(v->confirmBulkSave());
        status=label("I tag preesistenti di ciascuna foto saranno conservati. Prova prima con copie JPEG.",13,false);
        root.addView(status);
        root.addView(label("I tag restano memorizzati nell'app per le selezioni future. Non è previsto un limite artificiale al numero di fotografie; grandi selezioni richiedono più tempo e spazio libero.",12,false));
        setContentView(scroll);refreshLibrary();
    }
    private TextView label(String s,int size,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(30,41,59));
        if(bold)t.setTypeface(null,1);t.setPadding(0,px(9),0,px(9));return t;
    }
    private Button button(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);return b;}
    private int px(int dp){return Math.round(getResources().getDisplayMetrics().density*dp);}
    private String findExisting(String tag){for(String x:library)if(x.equalsIgnoreCase(tag))return x;return null;}
    private void refreshLibrary(){
        if(tagList==null)return;
        tagList.removeAllViews();String filter=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
        tagCount.setText(chosen.size()+" tag selezionati · "+library.size()+" tag disponibili");
        for(String tag:library){
            if(!tag.toLowerCase(Locale.ROOT).contains(filter))continue;
            CheckBox box=new CheckBox(this);box.setText(tag);box.setTextSize(16);box.setChecked(chosen.contains(tag));
            box.setOnCheckedChangeListener((view,on)->{if(on)chosen.add(tag);else chosen.remove(tag);tagCount.setText(chosen.size()+" tag selezionati · "+library.size()+" tag disponibili");updateButtons();});
            tagList.addView(box);
        }
        updateButtons();
    }
    private void updateButtons(){if(saveButton!=null)saveButton.setEnabled(!busy&&!photos.isEmpty()&&!chosen.isEmpty());if(selectButton!=null)selectButton.setEnabled(!busy);}
    private void choosePhotos(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_PHOTOS);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_PHOTOS||result!=RESULT_OK||data==null)return;
        photos.clear();LinkedHashSet<Uri> unique=new LinkedHashSet<>();
        if(data.getClipData()!=null){for(int j=0;j<data.getClipData().getItemCount();j++)unique.add(data.getClipData().getItemAt(j).getUri());}
        if(data.getData()!=null)unique.add(data.getData());
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        for(Uri u:unique){
            if(u==null)continue;
            photos.add(u);
            try{getContentResolver().takePersistableUriPermission(u,flags);}catch(Exception ignored){}
        }
        selectedCount.setText(photos.size()+" fotografie selezionate");
        status.setText("Pronto. I tag selezionati verranno aggiunti a ciascuna foto senza cancellare gli altri.");updateButtons();
    }
    private void confirmBulkSave(){
        if(photos.isEmpty()||chosen.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("Modificare "+photos.size()+" fotografie?")
            .setMessage("Aggiungerò "+chosen.size()+" tag a ciascun JPEG, conservando i tag XMP esistenti. Il salvataggio modifica gli originali: prova prima con copie di sicurezza. Procedere?")
            .setNegativeButton("Annulla",null).setPositiveButton("Applica",(d,w)->bulkSave()).show();
    }
    private void bulkSave(){
        busy=true;updateButtons();
        final ArrayList<Uri> targets=new ArrayList<>(photos);
        final ArrayList<String> toAdd=new ArrayList<>(chosen);
        worker.execute(()->{
            int ok=0,failed=0;String firstError=null;
            for(int i=0;i<targets.size();i++){
                final int index=i+1;
                runOnUiThread(()->status.setText("Elaborazione foto "+index+" di "+targets.size()+"..."));
                try{saveOne(targets.get(i),toAdd);ok++;}
                catch(Exception e){failed++;if(firstError==null)firstError=e.getMessage();}
            }
            final int done=ok,errors=failed;final String detail=firstError;
            runOnUiThread(()->{
                busy=false;updateButtons();
                status.setText("Completato: "+done+" foto aggiornate, "+errors+" errori."+(detail==null?"":" Primo errore: "+detail));
                if(errors==0)Toast.makeText(this,"Tag applicati a "+done+" foto",Toast.LENGTH_LONG).show();
            });
        });
    }
    private void saveOne(Uri u,List<String> extra)throws Exception{
        File original=File.createTempFile("fototag_src_",".jpg",getCacheDir());
        File updated=File.createTempFile("fototag_dst_",".jpg",getCacheDir());
        try{
            try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(original)){
                if(in==null)throw new IOException("Accesso alla fotografia negato");copy(in,out);
            }
            LinkedHashSet<String> all=new LinkedHashSet<>(JpegXmpEditor.readKeywords(original));
            all.addAll(extra);
            JpegXmpEditor.writeKeywords(original,updated,new ArrayList<>(all));
            // Verifica i metadati prima di sovrascrivere il documento selezionato.
            List<String> check=JpegXmpEditor.readKeywords(updated);
            if(!check.containsAll(all))throw new IOException("Verifica XMP non superata");
            try(OutputStream out=getContentResolver().openOutputStream(u,"wt");InputStream in=new FileInputStream(updated)){
                if(out==null)throw new IOException("File protetto da scrittura");copy(in,out);
            }catch(Exception writeError){
                try(OutputStream out=getContentResolver().openOutputStream(u,"wt");InputStream in=new FileInputStream(original)){
                    if(out==null)throw new IOException("Ripristino non disponibile");copy(in,out);
                }catch(Exception restore){throw new IOException("Scrittura fallita; impossibile garantire ripristino: "+writeError.getMessage(),restore);}
                throw writeError;
            }
        }finally{original.delete();updated.delete();}
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    private void loadLibrary(){
        try{JSONArray a=new JSONArray(getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY,"[]"));
            for(int i=0;i<a.length();i++){String v=a.optString(i).trim();if(!v.isEmpty())library.add(v);}
        }catch(Exception ignored){}
    }
    private void persistLibrary(){
        JSONArray array=new JSONArray();for(String t:library)array.put(t);
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY,array.toString()).apply();
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
