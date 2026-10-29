package org.frifra.vibedock.server;

import tools.jackson.databind.JsonNode;
import java.awt.*;
import java.util.*;
import java.util.List;
import static org.frifra.vibedock.server.ThemeDefinition.*;

/** Bounded vector decorations below content. No expressions, loops supplied by the file, text, URLs or scripts. */
record ThemeLayers(List<Shape> background,List<Shape> band) {
    static final ThemeLayers EMPTY=new ThemeLayers(List.of(),List.of());
    ThemeLayers {background=List.copyOf(background);band=List.copyOf(band);}
    record Length(double relative,double pixels) {
        int at(int extent){return (int)Math.round(relative*extent+pixels);}
        static Length read(JsonNode node){
            if(node!=null&&node.isNumber())return new Length(0,number(node,-4096,4096));
            fields(node,Set.of("relative","pixels"));
            return new Length(number(node.get("relative"),-4,4),number(node.get("pixels"),-4096,4096));
        }
    }
    record Shape(String op,Length x,Length y,Length width,Length height,int colour,float opacity,int radius,
                 boolean fill,int to,boolean vertical,int step) {
        void draw(Graphics2D g,int w,int h){
            int left=x.at(w),top=y.at(h),span=width.at(w),rows=height.at(h);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,opacity));g.setPaint(new Color(colour));
            switch(op){
                case "rect" -> {if(fill)g.fillRoundRect(left,top,span,rows,2*radius,2*radius);else g.drawRoundRect(left,top,span,rows,2*radius,2*radius);}
                case "ellipse" -> {if(fill)g.fillOval(left,top,span,rows);else g.drawOval(left,top,span,rows);}
                case "line" -> g.drawLine(left,top,left+span,top+rows);
                case "gradient" -> {
                    if(span<=0||rows<=0)return;
                    g.setPaint(new GradientPaint(left,top,new Color(colour),vertical?left:left+span,vertical?top+rows:top,new Color(to)));
                    g.fillRect(left,top,span,rows);
                }
                case "grid" -> {
                    for(int n=0;n<512&&n*step<=span;n++)g.drawLine(left+n*step,top,left+n*step,top+rows);
                    for(int n=0;n<512&&n*step<=rows;n++)g.drawLine(left,top+n*step,left+span,top+n*step);
                }
                default -> throw new IllegalStateException("Unvalidated theme operation");
            }
        }
    }
    static ThemeLayers read(JsonNode node,Palette palette){
        if(node==null)return EMPTY;
        fields(node,Set.of("background","band"));
        return new ThemeLayers(shapes(node.get("background"),palette),shapes(node.get("band"),palette));
    }
    private static List<Shape> shapes(JsonNode list,Palette palette){
        if(list==null)return List.of();
        if(!list.isArray()||list.size()>32)throw invalid("At most 32 operations per theme layer");
        var out=new ArrayList<Shape>();
        for(var node:list){
            if(!node.isObject())throw invalid("Expected a theme shape");
            String op=text(node,"op");var allowed=new HashSet<>(Set.of("op","x","y","width","height","color","opacity"));
            switch(op){case "rect"->allowed.addAll(Set.of("radius","fill"));case "ellipse"->allowed.add("fill");
                case "gradient"->allowed.addAll(Set.of("to","vertical"));case "grid"->allowed.add("step");case "line"->{}
                default->throw invalid("Unsupported theme operation: "+op);}
            fields(node,allowed);
            int radius=node.has("radius")?integer(node.get("radius"),0,128):0;
            int step=node.has("step")?integer(node.get("step"),1,128):8;
            out.add(new Shape(op,Length.read(node.get("x")),Length.read(node.get("y")),Length.read(node.get("width")),Length.read(node.get("height")),
                    colour(text(node,"color"),palette),node.has("opacity")?(float)number(node.get("opacity"),0,1):1,radius,
                    bool(node,"fill",true),op.equals("gradient")?colour(text(node,"to"),palette):0,bool(node,"vertical",true),step));
        }
        return List.copyOf(out);
    }
    private static int integer(JsonNode node,int low,int high){
        double value=number(node,low,high);if(value!=Math.rint(value))throw invalid("Expected whole-number theme size");return (int)value;
    }
    private static boolean bool(JsonNode node,String field,boolean fallback){
        var value=node.get(field);if(value==null)return fallback;
        if(!value.isBoolean())throw invalid("Expected a theme boolean: "+field);return value.booleanValue();
    }
    void drawBackground(Graphics2D g,int width,int height){draw(background,g,0,0,width,height);}
    void drawBand(Graphics2D g,int x,int y,int width,int height){draw(band,g,x,y,width,height);}
    private static void draw(List<Shape> shapes,Graphics2D parent,int x,int y,int width,int height){
        if(shapes.isEmpty())return;
        var g=(Graphics2D)parent.create();
        try{g.translate(x,y);g.clipRect(0,0,width,height);for(var shape:shapes)shape.draw(g,width,height);}
        finally{g.dispose();}
    }
}
