package com.supremacy.board;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/** An original nautical atlas, rendered entirely in libGDX from geographic polygons. */
public final class AtlasApp extends ApplicationAdapter {
    private static final Color INK=Color.valueOf("0D1C29"), PANEL=Color.valueOf("132736"),
        OCEAN=Color.valueOf("162E3C"), WHITE=Color.valueOf("E8E6DB"), MUTED=Color.valueOf("89A3AF"),
        GOLD=Color.valueOf("E4C484"), GRID=Color.valueOf("213E4C");
    private final FitViewport viewport=new FitViewport(1600,1000);
    private final AtlasCamera camera=new AtlasCamera();
    private final List<Hit> hits=new ArrayList<>();
    private final Map<Board.Connection,List<Vector2>> lanes=new HashMap<>();
    private final Map<Board.Region,Color> colors=new HashMap<>();
    private final GlyphLayout layout=new GlyphLayout();
    private final Vector2 mouse=new Vector2();
    private Board board;
    private Board.Region selected,hovered;
    private Board.Continent continent;
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private Texture pixel;
    private static final Map<String,String> OVERVIEW_LABELS=Map.ofEntries(
        Map.entry("British Columbia","B.C."),Map.entry("Alberta","AB"),Map.entry("Saskatchewan","SK"),
        Map.entry("Manitoba","MB"),Map.entry("Ontario","ON"),Map.entry("Quebec","QC"),
        Map.entry("Northwest Territories","N.W.T."),Map.entry("Newfoundland and Labrador","N.L."),
        Map.entry("Western Australia","W.A."),Map.entry("Northern Territory","N.T."),
        Map.entry("South Australia","S.A."),Map.entry("Queensland","QLD"),
        Map.entry("New South Wales","N.S.W."),Map.entry("Victoria","VIC"),Map.entry("Tasmania","TAS"));
    private BitmapFont small,body,title,heading;
    private boolean showRoutes=true,showLabels=true,help,searching;
    private String search="";
    private float downX,downY,lastX,lastY,dragDistance;
    private int frames;
    private record Hit(Rectangle rect,Runnable action) {}

    @Override public void create() {
        board=new Board(new InputStreamReader(Gdx.files.internal("board.json").read(),StandardCharsets.UTF_8));
        shapes=new ShapeRenderer(12000); batch=new SpriteBatch();
        Pixmap dot=new Pixmap(1,1,Pixmap.Format.RGBA8888);dot.setColor(Color.WHITE);dot.fill();pixel=new Texture(dot);dot.dispose();
        small=font("NotoSans-Regular.ttf",13); body=font("NotoSans-Regular.ttf",16);
        title=font("NotoSerif-Regular.ttf",35); heading=font("NotoSerif-Regular.ttf",25);
        for (Board.Region r:board.regions) {
            Color color=Color.valueOf(r.continent.color());
            color.lerp(WHITE,Math.floorMod(r.id.hashCode(),4)*.035f);
            colors.put(r,color);
        }
        for (Board.Connection e:board.connections) if(e.kind()==Board.Kind.SEA) lanes.put(e,curve(e));
        Gdx.input.setInputProcessor(new Controls());
        // This board has no animation: redraw on input/resize instead of using
        // GPU time while idle. Screenshot mode requests eight consecutive frames.
        Gdx.graphics.setContinuousRendering(System.getProperty("supremacy.screenshot")!=null);
        viewport.update(Gdx.graphics.getWidth(),Gdx.graphics.getHeight(),true);
    }
    private BitmapFont font(String name,int size) {
        FreeTypeFontGenerator g=new FreeTypeFontGenerator(Gdx.files.internal("fonts/"+name));
        var p=new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size=size; p.characters+="—−×·…"; p.minFilter=Texture.TextureFilter.Linear;p.magFilter=Texture.TextureFilter.Linear;
        BitmapFont f=g.generateFont(p);g.dispose();return f;
    }
    @Override public void resize(int width,int height) { viewport.update(width,height,true); }
    @Override public void render() {
        ScreenUtils.clear(INK);
        viewport.apply(); shapes.setProjectionMatrix(viewport.getCamera().combined);batch.setProjectionMatrix(viewport.getCamera().combined);
        mouse.set(Gdx.input.getX(),Gdx.input.getY());viewport.unproject(mouse);
        hovered=help?null:pick(mouse.x,mouse.y);
        hits.clear();
        Gdx.gl.glEnable(GL20.GL_BLEND);Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,GL20.GL_ONE_MINUS_SRC_ALPHA);
        drawMap(); drawChrome(); drawSidebar(); drawLegend();
        if(help)drawHelp();
        frames++;
        // Deterministic desktop screenshot for reviews and smoke testing.
        String screenshot=System.getProperty("supremacy.screenshot");
        if(screenshot!=null && frames==8) {
            Pixmap pixmap=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.getBackBufferWidth(),Gdx.graphics.getBackBufferHeight());
            PixmapIO.writePNG(Gdx.files.absolute(screenshot),pixmap,-1,true);pixmap.dispose();
            Gdx.app.exit();
        }
    }
    private void begin(){shapes.begin(ShapeRenderer.ShapeType.Filled);}
    private void rect(float x,float y,float w,float h,Color c){shapes.setColor(c);shapes.rect(x,y,w,h);}
    private void line(float x,float y,float xx,float yy,float width,Color c){shapes.setColor(c);shapes.rectLine(x,y,xx,yy,width);}
    private void ring(float x,float y,float radius,float width,Color c) {
        for(int i=0;i<24;i++){float a=i*MathUtils.PI2/24,b=(i+1)*MathUtils.PI2/24;line(x+MathUtils.cos(a)*radius,y+MathUtils.sin(a)*radius,x+MathUtils.cos(b)*radius,y+MathUtils.sin(b)*radius,width,c);}
    }
    private void text(BitmapFont f,String value,float x,float y,Color color) {
        f.setColor(color);f.draw(batch,value,x,y);
    }
    private void centered(BitmapFont f,String value,float x,float y,Color color) {
        f.setColor(color);f.draw(batch,value,x-140,y,280,Align.center,false);
    }
    private void wrap(BitmapFont f,String value,float x,float y,float width,Color color) {
        f.setColor(color);f.draw(batch,value,x,y,width,Align.left,true);
    }
    private static Color alpha(Color c,float a){return new Color(c.r,c.g,c.b,a);}
    private boolean visible(Board.Region r){return (continent==null||r.continent==continent)&&(search.isBlank()||r.name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)));}
    private boolean neighbour(Board.Region r){return selected!=null&&board.links(selected).stream().anyMatch(e->e.other(selected)==r);}
    private void clipMap(boolean on) {
        if(on){
            Vector2 p=viewport.project(new Vector2(AtlasCamera.X,AtlasCamera.Y));
            Vector2 q=viewport.project(new Vector2(AtlasCamera.X+AtlasCamera.WIDTH,AtlasCamera.Y+AtlasCamera.HEIGHT));
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);HdpiUtils.glScissor(Math.round(p.x),Math.round(p.y),Math.round(q.x-p.x),Math.round(q.y-p.y));
        } else Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }
    private void drawMap() {
        clipMap(true); begin();
        rect(AtlasCamera.X,AtlasCamera.Y,AtlasCamera.WIDTH,AtlasCamera.HEIGHT,OCEAN);
        // Subtle atlas graticule and hand-drawn wave marks stay attached to the board.
        for(int x=0;x<=1800;x+=150)line(camera.screenX(x),camera.screenY(0),camera.screenX(x),camera.screenY(750),.65f,GRID);
        for(int y=0;y<=750;y+=100)line(camera.screenX(0),camera.screenY(y),camera.screenX(1800),camera.screenY(y),.65f,GRID);
        Random rng=new Random(2904);
        for(int i=0;i<360;i++) {
            float x=camera.screenX(rng.nextFloat()*1800),y=camera.screenY(rng.nextFloat()*750),s=camera.scale();
            line(x,y,x+3*s,y-1*s,.65f,alpha(MUTED,.13f));line(x+3*s,y-1*s,x+6*s,y,.65f,alpha(MUTED,.13f));
        }
        if(showRoutes)for(Board.Connection e:board.connections)if(e.kind()==Board.Kind.SEA)drawLane(e,false);
        // Shadow gives coastlines depth while keeping the camera strictly overhead.
        for(Board.Region r:board.regions) fill(r,alpha(Color.BLACK,.3f),2,-3);
        for(Board.Region r:board.regions) {
            Color c=new Color(colors.get(r));
            if(!visible(r))c.lerp(OCEAN,.82f);
            else if(selected!=null && r!=selected&&!neighbour(r))c.lerp(OCEAN,.46f);
            else if(r==selected)c.lerp(WHITE,.32f);
            else if(r==hovered)c.lerp(WHITE,.22f);
            fill(r,c,0,0);
            outline(r,r==selected?WHITE:Color.valueOf("263D45"),r==selected?2:1);
        }
        // Highlighted lanes are drawn over land to identify their exact endpoints.
        if(showRoutes && selected!=null)for(Board.Connection e:board.links(selected))if(e.kind()==Board.Kind.SEA)drawLane(e,true);
        for(Board.Region r:board.regions) {
            if(!visible(r))continue;
            float x=camera.screenX(r.anchor.x),y=camera.screenY(r.anchor.y);
            if(r==selected||neighbour(r))ring(x,y,5,1.3f,r==selected?WHITE:GOLD);
            else if(r.name.equals("Prince Edward Island")||r.name.equals("Hawaii"))ring(x,y,4,1,GOLD);
        }
        compass(camera.screenX(230),camera.screenY(150),42);
        ship(camera.screenX(555),camera.screenY(425),.9f);
        ship(camera.screenX(1240),camera.screenY(175),1);
        shapes.end();
        batch.begin();
        ocean("P A C I F I C   O C E A N",190,350);
        ocean("A T L A N T I C",700,440);ocean("O C E A N",700,407);
        ocean("I N D I A N   O C E A N",1300,165);
        ocean("S O U T H   P A C I F I C",420,120);
        ocean("A R C T I C   O C E A N",900,735);
        if(showLabels)drawLabels();
        if(showRoutes&&selected!=null) {
            int i=0;
            for(Board.Connection e:board.links(selected))if(e.kind()==Board.Kind.SEA){
                i++;List<Vector2> p=lanes.get(e);Vector2 mid=p.get(p.size()/2);
                float x=camera.screenX(wrapX(mid.x)),y=camera.screenY(mid.y);
                centered(small,String.format(Locale.ROOT,"%02d",i),x,y+6,GOLD);
            }
        }
        batch.end();clipMap(false);
    }
    private void ocean(String name,float x,float y){centered(small,name,camera.screenX(x),camera.screenY(y),alpha(MUTED,.42f));}
    private void fill(Board.Region r,Color color,float dx,float dy) {
        shapes.setColor(color);
        for(int k=0;k<r.polygons.size();k++) {
            float[] p=r.polygons.get(k);short[] triangles=r.triangles.get(k);
            for(int i=0;i<triangles.length;i+=3) {
                int a=triangles[i]*2,b=triangles[i+1]*2,c=triangles[i+2]*2;
                shapes.triangle(camera.screenX(p[a])+dx,camera.screenY(p[a+1])+dy,camera.screenX(p[b])+dx,camera.screenY(p[b+1])+dy,camera.screenX(p[c])+dx,camera.screenY(p[c+1])+dy);
            }
        }
    }
    private void outline(Board.Region r,Color color,float width) {
        for(float[] p:r.polygons)for(int i=0;i<p.length;i+=2){int j=(i+2)%p.length;line(camera.screenX(p[i]),camera.screenY(p[i+1]),camera.screenX(p[j]),camera.screenY(p[j+1]),width,color);}
    }
    private void drawLabels() {
        List<Rectangle> occupied=new ArrayList<>();
        List<Board.Region> order=new ArrayList<>(board.regions);
        order.sort(Comparator.comparingInt(r->r==selected?-2:r==hovered?-1:0));
        for(Board.Region r:order) {
            if(!visible(r))continue;
            float x=camera.screenX(r.anchor.x), y=camera.screenY(r.anchor.y)+6;
            if(x<AtlasCamera.X||x>AtlasCamera.X+AtlasCamera.WIDTH||y<AtlasCamera.Y||y>AtlasCamera.Y+AtlasCamera.HEIGHT)continue;
            float scale=camera.zoom>2?1:.83f;
            String label=camera.zoom<1.8f?OVERVIEW_LABELS.getOrDefault(r.name,r.label):r.label;
            small.getData().setScale(scale);layout.setText(small,label);
            float w=layout.width,h=layout.height;
            Rectangle bounds=new Rectangle(x-w/2-3,y-h-3,w+6,h+7);
            boolean collision=occupied.stream().anyMatch(b->b.overlaps(bounds));
            if(collision && r!=selected&&r!=hovered)continue;
            occupied.add(bounds);
            Vector2 left=camera.world(x-w/2,y-h/2),right=camera.world(x+w/2,y-h/2);
            boolean coastal=!r.contains(r.anchor.x,r.anchor.y)||!r.contains(left.x,left.y)||!r.contains(right.x,right.y);
            if(coastal){batch.setColor(alpha(INK,.80f));batch.draw(pixel,bounds.x,bounds.y,bounds.width,bounds.height);batch.setColor(Color.WHITE);}
            centered(small,label,x,y,coastal?WHITE:selected!=null&&r!=selected&&!neighbour(r)?MUTED:INK);
        }
        small.getData().setScale(1);
    }
    private float wrapX(float x){return (x%1800+1800)%1800;}
    private List<Vector2> curve(Board.Connection edge) {
        List<Vector2> controls=new ArrayList<>();controls.add(new Vector2(edge.a().anchor));
        edge.via().forEach(v->controls.add(new Vector2(v)));controls.add(new Vector2(edge.b().anchor));
        // Unwrap Pacific waypoints, then split rendering at the date line.
        for(int i=1;i<controls.size();i++){
            Vector2 a=controls.get(i-1),b=controls.get(i);
            while(b.x-a.x>900)b.x-=1800;while(b.x-a.x < -900)b.x+=1800;
        }
        List<Vector2> result=new ArrayList<>();
        for(int i=0;i<controls.size()-1;i++) {
            Vector2 p0=controls.get(Math.max(0,i-1)),p1=controls.get(i),p2=controls.get(i+1),p3=controls.get(Math.min(controls.size()-1,i+2));
            int steps=Math.max(8,(int)(p1.dst(p2)/3));
            for(int j=0;j<steps;j++) {
                float t=j/(float)steps,t2=t*t,t3=t2*t;
                result.add(new Vector2(.5f*((2*p1.x)+(-p0.x+p2.x)*t+(2*p0.x-5*p1.x+4*p2.x-p3.x)*t2+(-p0.x+3*p1.x-3*p2.x+p3.x)*t3),
                    .5f*((2*p1.y)+(-p0.y+p2.y)*t+(2*p0.y-5*p1.y+4*p2.y-p3.y)*t2+(-p0.y+3*p1.y-3*p2.y+p3.y)*t3)));
            }
        }
        result.add(controls.get(controls.size()-1));return result;
    }
    private void drawLane(Board.Connection edge,boolean highlight) {
        List<Vector2> path=lanes.get(edge);
        Color c=alpha(GOLD,highlight?.95f:selected==null?.57f:.13f);
        float length=0;
        for(int i=1;i<path.size();i++) {
            Vector2 a=path.get(i-1),b=path.get(i);
            length+=a.dst(b)*camera.scale();
            float ax=wrapX(a.x),bx=wrapX(b.x);
            if(Math.abs(ax-bx)>900)continue;
            if(length%12<7)line(camera.screenX(ax),camera.screenY(a.y),camera.screenX(bx),camera.screenY(b.y),highlight?2:1.2f,c);
        }
        if(highlight)for(Board.Region r:List.of(edge.a(),edge.b()))ring(camera.screenX(r.anchor.x),camera.screenY(r.anchor.y),7,1.2f,GOLD);
    }
    private void compass(float x,float y,float r) {
        Color c=alpha(MUTED,.4f);ring(x,y,r,.7f,c);ring(x,y,r-6,.6f,c);
        for(int i=0;i<8;i++){float a=i*MathUtils.PI/4,xx=x+MathUtils.sin(a)*r*1.2f,yy=y+MathUtils.cos(a)*r*1.2f;line(x,y,xx,yy,.7f,c);}
        shapes.setColor(c);shapes.triangle(x,y+r*1.2f,x-5,y,x,y-5);shapes.triangle(x,y-r*1.2f,x+5,y,x,y+5);
    }
    private void ship(float x,float y,float s) {
        Color c=alpha(MUTED,.27f);
        line(x-14*s,y,x+14*s,y,.9f,c);line(x-14*s,y,x-7*s,y-5*s,.9f,c);line(x-7*s,y-5*s,x+8*s,y-5*s,.9f,c);line(x+8*s,y-5*s,x+14*s,y,.9f,c);
        line(x,y,x,y+27*s,.8f,c);line(x,y+27*s,x-13*s,y+5*s,.8f,c);line(x-13*s,y+5*s,x,y+5*s,.8f,c);line(x+3*s,y+23*s,x+13*s,y+5*s,.8f,c);line(x+13*s,y+5*s,x+3*s,y+5*s,.8f,c);
    }
    private void button(String label,float x,float y,float w,boolean active,Runnable action) {
        begin();rect(x,y,w,34,active?Color.valueOf("31464D"):PANEL);
        if(active)rect(x,y,w,2,GOLD);shapes.end();
        batch.begin();centered(small,label,x+w/2,y+23,active?GOLD:MUTED);batch.end();
        hits.add(new Hit(new Rectangle(x,y,w,34),action));
    }
    private void drawChrome() {
        begin();rect(28,897,1544,1,GRID);rect(28,119,1544,1,GRID);
        // Emblem: a small compass rose rather than a borrowed game logo.
        compass(48,944,16);shapes.end();
        batch.begin();text(title,"SUPREMACY",83,969,WHITE);text(small,"A WORLD WAITING TO BE CONQUERED",85,923,MUTED);
        text(small,"WORLD ATLAS   /   01",28,887,GOLD);
        text(small,board.regions.size()+" TERRITORIES     "+board.continents.size()+" CONTINENTS",880,887,MUTED);
        text(small,"BOARD STUDY  /  NO GAME RULES",1274,887,MUTED);
        text(small,"Drag to pan   ·   Scroll to zoom   ·   Click a territory to inspect",28,105,MUTED);
        text(small,"TOP-DOWN  /  ORTHOGRAPHIC",1016,105,MUTED);
        text(small,"NATURAL EARTH GEOGRAPHY",28,22,alpha(MUTED,.55f));
        text(small,"GAME OF SUPREMACY     /     CUSTOM WORLD BOARD",1190,22,alpha(MUTED,.55f));batch.end();
        button("SEA LANES  "+(showRoutes?"ON":"OFF"),940,931,146,showRoutes,()->showRoutes=!showRoutes);
        button("LABELS  "+(showLabels?"ON":"OFF"),1098,931,132,showLabels,()->showLabels=!showLabels);
        button("RESET VIEW",1242,931,146,false,()->{camera.reset();selected=null;continent=null;search="";searching=false;});
        button("?   GUIDE",1400,931,172,help,()->help=!help);
        button("−",1124,150,32,false,()->camera.zoomAt(1/1.25f,636,499));
        button("+",1164,150,32,false,()->camera.zoomAt(1.25f,636,499));
        batch.begin();text(small,String.format(Locale.ROOT,"%.1f×",camera.zoom),1080,173,MUTED);batch.end();
    }
    private void drawSidebar() {
        begin();rect(1264,134,308,730,PANEL);rect(1284,790,268,40,INK);shapes.end();
        batch.begin();text(small,"TERRITORY INDEX",1284,849,GOLD);
        text(body,search.isEmpty()?(searching?"Type a territory...":"Search territories   /"):search+(searching?"|":""),1296,817,search.isEmpty()?MUTED:WHITE);batch.end();
        hits.add(new Hit(new Rectangle(1284,790,268,40),()->searching=true));
        if(!search.isBlank()) {
            List<Board.Region> results=board.regions.stream().filter(r->r.name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).toList();
            batch.begin();text(small,results.size()+" MATCHING TERRITORIES",1284,766,MUTED);batch.end();
            for(int i=0;i<Math.min(results.size(),12);i++) {
                Board.Region r=results.get(i);float y=713-i*43;
                button(r.name,1284,y,268,r==selected,()->{select(r);camera.focus(r);search="";searching=false;});
            }
            return;
        }
        Board.Region current=selected!=null?selected:hovered;
        if(current==null) { drawOverview();return; }
        Color color=colors.get(current);
        begin();rect(1284,751,25,3,color);shapes.end();
        batch.begin();text(small,current.continent.name().toUpperCase(Locale.ROOT),1320,762,color);
        wrap(heading,current.name,1284,733,268,WHITE);
        text(small,selected!=null?"SELECTED TERRITORY":"HOVER PREVIEW  /  CLICK TO PIN",1284,662,MUTED);
        text(small,"CONNECTED TERRITORIES",1284,620,GOLD);batch.end();
        List<Board.Connection> links=board.links(current);int sea=0;
        // Keep all neighbours accessible; the largest region has fewer than 14.
        for(int i=0;i<links.size();i++) {
            Board.Connection e=links.get(i);Board.Region other=e.other(current);
            float y=590-i*25;String badge=e.kind()==Board.Kind.SEA?String.format(Locale.ROOT,"%02d",++sea):"—";
            batch.begin();text(small,badge,1284,y,e.kind()==Board.Kind.SEA?GOLD:MUTED);
            text(small,other.name,1314,y,WHITE);text(small,e.kind()==Board.Kind.SEA?"SEA":"LAND",1518,y,e.kind()==Board.Kind.SEA?GOLD:MUTED);batch.end();
            hits.add(new Hit(new Rectangle(1284,y-20,268,25),()->{select(other);camera.focus(other);}));
        }
        float summaryY=Math.min(250,570-links.size()*25);
        batch.begin();text(small,links.size()+" CONNECTIONS   /   "+sea+" SEA LANES",1284,summaryY,GOLD);
        String includes=current.includes.length()>125?current.includes.substring(0,122)+"...":current.includes;
        wrap(small,"Includes: "+includes,1284,summaryY-28,268,MUTED);batch.end();
    }
    private void drawOverview() {
        batch.begin();text(heading,"Explore the world.",1284,748,WHITE);
        wrap(body,"Choose a territory to trace its borders and sea connections.",1284,701,260,MUTED);
        text(small,"THE CUSTOM BOARD",1284,603,GOLD);
        text(title,String.valueOf(board.regions.size()),1284,567,WHITE);text(small,"TERRITORIES",1370,547,MUTED);
        text(title,"07",1284,504,WHITE);text(small,"CONTINENTS",1370,484,MUTED);
        text(title,String.valueOf(board.seaCount()),1284,441,WHITE);text(small,"SEA LANES",1370,421,MUTED);
        text(small,"READING THE ATLAS",1284,349,GOLD);
        text(body,"Shared land border",1320,311,WHITE);text(body,"Sea connection",1320,270,WHITE);
        wrap(small,"Gold dashes cross the ocean. Select a territory to highlight and number its sea lanes.",1284,225,260,MUTED);batch.end();
        begin();line(1284,302,1307,302,2,MUTED);line(1284,262,1292,262,2,GOLD);line(1299,262,1307,262,2,GOLD);shapes.end();
    }
    private void drawLegend() {
        float[] widths={248,248,200,188,176,226,258};float x=28;
        for(int i=0;i<board.continents.size();i++) {
            Board.Continent c=board.continents.get(i);Color color=Color.valueOf(c.color());float w=widths[i];
            int count=(int)board.regions.stream().filter(r->r.continent==c).count();
            begin();rect(x,47,w-10,39,continent==c?Color.valueOf("30414A"):PANEL);rect(x+12,60,10,10,color);shapes.end();
            batch.begin();text(small,c.name(),x+32,73,WHITE);text(small,String.format(Locale.ROOT,"%02d",count),x+w-40,73,MUTED);batch.end();
            hits.add(new Hit(new Rectangle(x,47,w-10,39),()->{continent=continent==c?null:c;selected=null;search="";searching=false;}));x+=w;
        }
    }
    private void drawHelp() {
        begin();rect(0,0,1600,1000,alpha(INK,.88f));rect(450,255,700,490,PANEL);shapes.end();
        batch.begin();text(heading,"A closer look at the world",490,703,WHITE);
        String[] lines={"Click a territory  —  pin its neighbours and numbered sea lanes.","Drag the board  —  pan. Scroll or + / −  —  zoom.","Click a neighbour  —  jump to that territory.","Click a continent  —  filter its territories. Click again to clear.","/  —  search. Enter  —  focus the first match.","L  —  labels. S  —  sea lanes. Home / R  —  reset the view.","Escape  —  close guide, clear search, then selection.","The camera always stays directly above the board."};
        for(int i=0;i<lines.length;i++)text(body,lines[i],490,644-i*37,MUTED);batch.end();
        // Modal: prevent clicks from reaching the board controls behind it.
        hits.clear();button("BACK TO THE ATLAS",490,285,620,true,()->help=false);
    }
    private void select(Board.Region r){selected=r;continent=null;}
    private Board.Region pick(float x,float y) {
        if(!camera.inside(x,y))return null;
        Vector2 p=camera.world(x,y);
        // Small-island anchors receive a screen-space target so PEI and Hawaii
        // remain selectable even at the overview scale.
        Board.Region nearest=null;float distance=7*7;
        for(Board.Region r:board.regions)if(visible(r)) {
            float dx=camera.screenX(r.anchor.x)-x,dy=camera.screenY(r.anchor.y)-y,d=dx*dx+dy*dy;
            if(d<distance){nearest=r;distance=d;}
        }
        if(nearest!=null)return nearest;
        for(Board.Region r:board.regions)if(visible(r)&&r.contains(p.x,p.y))return r;
        return null;
    }
    private class Controls extends InputAdapter {
        private Vector2 point(int x,int y){return viewport.unproject(new Vector2(x,y));}
        @Override public boolean touchDown(int x,int y,int pointer,int button) {
            Vector2 p=point(x,y);downX=lastX=p.x;downY=lastY=p.y;dragDistance=0;return true;
        }
        @Override public boolean touchDragged(int x,int y,int pointer) {
            Vector2 p=point(x,y);dragDistance=Math.max(dragDistance,Vector2.dst(downX,downY,p.x,p.y));
            if(!help&&camera.inside(downX,downY)&&dragDistance>4)camera.pan(p.x-lastX,p.y-lastY);
            lastX=p.x;lastY=p.y;return true;
        }
        @Override public boolean touchUp(int x,int y,int pointer,int button) {
            if(dragDistance>4)return true;Vector2 p=point(x,y);
            for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).rect.contains(p)){hits.get(i).action.run();return true;}
            if(!help&&camera.inside(p.x,p.y)){selected=pick(p.x,p.y);searching=false;}
            return true;
        }
        @Override public boolean scrolled(float dx,float dy) {
            if(!help&&camera.inside(mouse.x,mouse.y))camera.zoomAt((float)Math.pow(1.18,-dy),mouse.x,mouse.y);return true;
        }
        @Override public boolean keyDown(int key) {
            if(key==Input.Keys.ESCAPE){if(help)help=false;else if(searching||!search.isEmpty()){search="";searching=false;}else{selected=null;continent=null;}return true;}
            if(help)return true;
            if(searching) {
                if(key==Input.Keys.BACKSPACE&&!search.isEmpty())search=search.substring(0,search.length()-1);
                if(key==Input.Keys.ENTER)board.regions.stream().filter(r->r.name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).findFirst().ifPresent(r->{select(r);camera.focus(r);search="";searching=false;});
                return true;
            }
            switch(key){
                case Input.Keys.SLASH -> searching=true;
                case Input.Keys.L -> showLabels=!showLabels;
                case Input.Keys.S -> showRoutes=!showRoutes;
                case Input.Keys.R,Input.Keys.HOME -> {camera.reset();continent=null;selected=null;search="";}
                case Input.Keys.PLUS,Input.Keys.EQUALS -> camera.zoomAt(1.25f,636,499);
                case Input.Keys.MINUS -> camera.zoomAt(.8f,636,499);
                case Input.Keys.F1 -> help=true;
                default -> {return false;}
            }return true;
        }
        @Override public boolean keyTyped(char c) {
            if(searching&&c>=32&&c!=127&&c!='/'&&search.length()<28)search+=c;return true;
        }
    }
    @Override public void dispose(){shapes.dispose();batch.dispose();pixel.dispose();small.dispose();body.dispose();heading.dispose();title.dispose();}
}
