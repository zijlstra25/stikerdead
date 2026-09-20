package com.stikerdead.keyboard;

import android.content.Context;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class PredictionEngine {
    private final Context context;
    private final Map<String,Integer> words = new HashMap<>();
    private final Map<String,Map<String,Integer>> next = new HashMap<>();

    public PredictionEngine(Context context) {
        this.context = context.getApplicationContext();
        seed();
    }

    public String suggest(String prefix, String previous) {
        prefix = norm(prefix);
        previous = norm(previous);
        if (prefix.isEmpty()) {
            String n = bestNext(previous);
            return n.isEmpty() ? "hola" : n;
        }
        String best = "";
        int score = -1;
        for (Map.Entry<String,Integer> e : words.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() > score) {
                best = e.getKey();
                score = e.getValue();
            }
        }
        return best.isEmpty() ? correction(prefix) : best;
    }

    public void learn(String word, String previous) {
        word = norm(word);
        previous = norm(previous);
        if (word.isEmpty()) return;
        words.put(word, words.containsKey(word) ? words.get(word)+1 : 1);
        if (!previous.isEmpty()) {
            Map<String,Integer> m = next.get(previous);
            if (m == null) { m = new HashMap<>(); next.put(previous,m); }
            m.put(word, m.containsKey(word) ? m.get(word)+1 : 1);
        }
    }

    private String bestNext(String previous) {
        Map<String,Integer> m = next.get(previous);
        if (m == null) return "";
        String best = ""; int score = -1;
        for (Map.Entry<String,Integer> e : m.entrySet()) {
            if (e.getValue() > score) { score=e.getValue(); best=e.getKey(); }
        }
        return best;
    }

    private String correction(String s) {
        if (s.length() < 3) return "";
        String best=""; int bd=3, bf=-1;
        for (Map.Entry<String,Integer> e : words.entrySet()) {
            if (Math.abs(e.getKey().length()-s.length()) > 2) continue;
            int d=distance(s,e.getKey());
            if (d<bd || (d==bd && e.getValue()>bf)) {
                bd=d; bf=e.getValue(); best=e.getKey();
            }
        }
        return bd<=2 ? best : "";
    }

    private int distance(String a,String b) {
        int[] p=new int[b.length()+1], c=new int[b.length()+1];
        for(int j=0;j<=b.length();j++) p[j]=j;
        for(int i=1;i<=a.length();i++){
            c[0]=i;
            for(int j=1;j<=b.length();j++){
                int cost=a.charAt(i-1)==b.charAt(j-1)?0:1;
                c[j]=Math.min(Math.min(c[j-1]+1,p[j]+1),p[j-1]+cost);
            }
            int[] t=p;p=c;c=t;
        }
        return p[b.length()];
    }

    private void add(String w) { words.put(w,1); }
    private void pair(String a,String b,int n) {
        Map<String,Integer> m=next.get(a);
        if(m==null){m=new HashMap<>();next.put(a,m);}
        m.put(b,n);
    }

    private void seed() {
        String[] ws={
            "a","al","algo","antes","aquí","así","bien","cada","casa","como","cómo","con",
            "cuando","cuándo","de","del","desde","donde","dónde","el","ella","ellos","en","es",
            "esa","ese","eso","esta","está","este","esto","favor","fue","gracias","hola","hace",
            "hacia","hay","he","hoy","la","las","le","lo","los","más","me","mi","mismo","muy",
            "necesito","no","nos","nosotros","para","pero","por","porque","que","qué","quiero",
            "se","si","sí","sin","sobre","soy","su","también","te","tengo","ti","todo","todos",
            "tu","tú","un","una","uno","vamos","ver","ya","yo","puede","puedo","puedes","quieres",
            "dame","decime","decir","hacer","ahora","después","mañana","noche","día","tiempo",
            "bueno","buena","grande","nuevo","nueva","mira","solo","estar","estoy","estás",
            "estamos","están","tenés","tenemos","tienen","tiene","juego","jugar","trabajo","foto",
            "mensaje","amigo","amiga","perdón","dale","listo","vení","viene","puerta","agua",
            "comida","también","muchas","días","noches","luego","vemos","tal","haces"
        };
        for(String w:ws)add(w);
        pair("hola","cómo",80); pair("hola","que",30); pair("cómo","estás",90);
        pair("qué","tal",70); pair("qué","quieres",35); pair("quiero","que",55);
        pair("quiero","una",40); pair("quiero","un",35); pair("para","que",70);
        pair("por","qué",60); pair("muchas","gracias",80); pair("buenos","días",80);
        pair("buenas","noches",80); pair("nos","vemos",60); pair("te","quiero",50);
        pair("hasta","luego",70); pair("qué","haces",50);
    }

    private String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }
}
