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
    static final Color INK=Color.valueOf("0D1C29"), PANEL=Color.valueOf("132736"),
        OCEAN=Color.valueOf("162E3C"), WHITE=Color.valueOf("E8E6DB"), MUTED=Color.valueOf("89A3AF"),
        GOLD=Color.valueOf("E4C484"), GRID=Color.valueOf("213E4C");
    private final FitViewport viewport=new FitViewport(1600,1000);
    final AtlasCamera camera=new AtlasCamera();
    final List<Hit> hits=new ArrayList<>();
    private final Map<Board.Connection,List<Vector2>> lanes=new HashMap<>();
    private final Map<Board.Region,Color> colors=new HashMap<>();
    private final GlyphLayout layout=new GlyphLayout();
    final Vector2 mouse=new Vector2();
    Board board;
    Board.Region selected,hovered;
    ShapeRenderer shapes;
    SpriteBatch batch;
    private Texture pixel;
    private static final Map<String,String> OVERVIEW_LABELS=Map.ofEntries(
        Map.entry("British Columbia","B.C."),Map.entry("Alberta","AB"),Map.entry("Saskatchewan","SK"),
        Map.entry("Manitoba","MB"),Map.entry("Ontario","ON"),Map.entry("Quebec","QC"),
        Map.entry("Northwest Territories","N.W.T."),Map.entry("Newfoundland and Labrador","N.L."),
        Map.entry("Western Australia","W.A."),Map.entry("Northern Territory","N.T."),
        Map.entry("South Australia","S.A."),Map.entry("Queensland","QLD"),
        Map.entry("New South Wales","N.S.W."),Map.entry("Victoria","VIC"),Map.entry("Tasmania","TAS"));
    BitmapFont small,body,title,heading,gameFont,counter,big;
    GameHud hud;
    private boolean showRoutes=true,showLabels=true;
    private float downX,downY,lastX,lastY,dragDistance;
    final List<Slider> sliders=new ArrayList<>();
    private Slider dragging;
    private int frames;
    record Hit(Rectangle rect,Runnable action) {}
    /** A horizontal control that reports the touch position as a 0..1 fraction while dragged. */
    record Slider(Rectangle rect,java.util.function.Consumer<Float> onChange) {
        void update(float x){onChange.accept(MathUtils.clamp((x-rect.x)/rect.width,0,1));}
    }

    @Override public void create() {
        board=new Board(new InputStreamReader(Gdx.files.internal("board.json").read(),StandardCharsets.UTF_8));
        shapes=new ShapeRenderer(12000); batch=new SpriteBatch();
        Pixmap dot=new Pixmap(1,1,Pixmap.Format.RGBA8888);dot.setColor(Color.WHITE);dot.fill();pixel=new Texture(dot);dot.dispose();
        small=font("NotoSans-Regular.ttf",13); body=font("NotoSans-Regular.ttf",16);
        title=font("NotoSerif-Regular.ttf",35); heading=font("NotoSerif-Regular.ttf",25);
        gameFont=font("NotoSans-Regular.ttf",21); big=font("NotoSans-Regular.ttf",40);
        counter=outlined("NotoSans-Regular.ttf",17);
        hud=new GameHud(this);
        for (Board.Region r:board.regions) {
            Color color=Color.valueOf(r.continent.color());
            color.lerp(WHITE,Math.floorMod(r.id.hashCode(),4)*.035f);
            colors.put(r,color);
        }
        for (Board.Connection e:board.connections) if(e.kind()==Board.Kind.SEA) lanes.put(e,curve(e));
        Gdx.input.setInputProcessor(new Controls());
        Gdx.input.setCatchKey(Input.Keys.BACK,true);
        // This board has no animation: redraw on input/resize instead of using
        // GPU time while idle. Screenshot mode requests eight consecutive frames.
        Gdx.graphics.setContinuousRendering(System.getProperty("supremacy.screenshot")!=null);
        viewport.update(Gdx.graphics.getWidth(),Gdx.graphics.getHeight(),true);
    }
    private BitmapFont font(String name,int size) {
        FreeTypeFontGenerator g=new FreeTypeFontGenerator(Gdx.files.internal("fonts/"+name));
        var p=new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size=size; p.characters+="—−×·…’→‹›•÷"; p.minFilter=Texture.TextureFilter.Linear;p.magFilter=Texture.TextureFilter.Linear;
        BitmapFont f=g.generateFont(p);g.dispose();return f;
    }
    /** Army numbers carry a dark outline so they read on every player colour and zoom level. */
    private BitmapFont outlined(String name,int size) {
        FreeTypeFontGenerator g=new FreeTypeFontGenerator(Gdx.files.internal("fonts/"+name));
        var p=new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size=size; p.characters="0123456789k+−-"; p.borderWidth=2f; p.borderColor=new Color(0.03f,0.07f,0.1f,0.9f);
        p.minFilter=Texture.TextureFilter.Linear;p.magFilter=Texture.TextureFilter.Linear;
        BitmapFont f=g.generateFont(p);g.dispose();return f;
    }
    @Override public void resize(int width,int height) { viewport.update(width,height,true); }
    @Override public void render() {
        ScreenUtils.clear(INK);
        viewport.apply(); shapes.setProjectionMatrix(viewport.getCamera().combined);batch.setProjectionMatrix(viewport.getCamera().combined);
        mouse.set(Gdx.input.getX(),Gdx.input.getY());viewport.unproject(mouse);
        hovered=hud.blocksBoard()?null:pick(mouse.x,mouse.y);
        hits.clear();sliders.clear();
        selected=hud.focus()<0?null:board.regions.get(hud.focus());
        Gdx.gl.glEnable(GL20.GL_BLEND);Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,GL20.GL_ONE_MINUS_SRC_ALPHA);
        drawMap(); hud.render();
        frames++;
        if(hud.animating())Gdx.graphics.requestRendering();
        // Deterministic desktop screenshot for reviews and smoke testing.
        String screenshot=System.getProperty("supremacy.screenshot");
        if(screenshot!=null && frames==8) {
            Pixmap pixmap=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.getBackBufferWidth(),Gdx.graphics.getBackBufferHeight());
            PixmapIO.writePNG(Gdx.files.absolute(screenshot),pixmap,-1,true);pixmap.dispose();
            Gdx.app.exit();
        }
    }
    // SpriteBatch.end() disables blending, so every shape pass re-enables it for translucent fills.
    void begin(){Gdx.gl.glEnable(GL20.GL_BLEND);Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,GL20.GL_ONE_MINUS_SRC_ALPHA);shapes.begin(ShapeRenderer.ShapeType.Filled);}
    void rect(float x,float y,float w,float h,Color c){shapes.setColor(c);shapes.rect(x,y,w,h);}
    void line(float x,float y,float xx,float yy,float width,Color c){shapes.setColor(c);shapes.rectLine(x,y,xx,yy,width);}
    /** Filled rounded rectangle; corners are pie slices so translucent colours do not double-blend. */
    void round(float x,float y,float w,float h,float r,Color c) {
        r=Math.max(0,Math.min(r,Math.min(w,h)/2));shapes.setColor(c);
        shapes.rect(x+r,y,w-2*r,h);shapes.rect(x,y+r,r,h-2*r);shapes.rect(x+w-r,y+r,r,h-2*r);
        if(r>0){shapes.arc(x+r,y+r,r,180,90,8);shapes.arc(x+w-r,y+r,r,270,90,8);shapes.arc(x+w-r,y+h-r,r,0,90,8);shapes.arc(x+r,y+h-r,r,90,90,8);}
    }
    void ring(float x,float y,float radius,float width,Color c) {
        for(int i=0;i<24;i++){float a=i*MathUtils.PI2/24,b=(i+1)*MathUtils.PI2/24;line(x+MathUtils.cos(a)*radius,y+MathUtils.sin(a)*radius,x+MathUtils.cos(b)*radius,y+MathUtils.sin(b)*radius,width,c);}
    }
    void text(BitmapFont f,String value,float x,float y,Color color) {
        f.setColor(color);f.draw(batch,value,x,y);
    }
    void centered(BitmapFont f,String value,float x,float y,Color color) {
        layout.setText(f,value);float w=layout.width;f.setColor(color);f.draw(batch,value,x-w/2,y,w,Align.center,false);
    }
    float width(BitmapFont f,String value){layout.setText(f,value);return layout.width;}
    void wrap(BitmapFont f,String value,float x,float y,float width,Color color) {
        f.setColor(color);f.draw(batch,value,x,y,width,Align.left,true);
    }
    static Color alpha(Color c,float a){return new Color(c.r,c.g,c.b,a);}
    private boolean visible(Board.Region r){return true;}
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
        for(int i=0;i<board.regions.size();i++) {
            Board.Region r=board.regions.get(i);
            Color c=new Color(colors.get(r));
            GameHud.Role role=hud.role(i);
            if(role==GameHud.Role.DIM)c.lerp(OCEAN,hud.dimStrength());
            else if(role==GameHud.Role.SOURCE)c.lerp(WHITE,.3f);
            else if(role==GameHud.Role.TARGET)c.lerp(WHITE,.2f);
            else if(r==hovered)c.lerp(WHITE,.16f);
            fill(r,c,0,0);
        }
        // Outlines are drawn after every fill so a highlighted border is never covered by a neighbour.
        for(int i=0;i<board.regions.size();i++)if(hud.role(i)!=GameHud.Role.SOURCE&&hud.role(i)!=GameHud.Role.TARGET)outline(board.regions.get(i),Color.valueOf("263D45"),1);
        for(int i=0;i<board.regions.size();i++){
            GameHud.Role role=hud.role(i);
            if(role==GameHud.Role.SOURCE)outline(board.regions.get(i),WHITE,2.5f);
            else if(role==GameHud.Role.TARGET)outline(board.regions.get(i),hud.actionColor(),2.5f);
        }
        // Highlighted lanes are drawn over land to identify their exact endpoints.
        if(showRoutes && selected!=null)for(Board.Connection e:board.links(selected))if(e.kind()==Board.Kind.SEA)drawLane(e,true);
        if(hud.game==null)for(Board.Region r:board.regions)
            if(r.name.equals("Prince Edward Island")||r.name.equals("Hawaii"))ring(camera.screenX(r.anchor.x),camera.screenY(r.anchor.y),4,1,GOLD);
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
        batch.end();
        drawCounters();
        batch.begin();
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
    /** Token radius grows gently with zoom so pieces stay legible without hiding the map. */
    float tokenRadius(){return Math.min(23,13f+3.2f*(camera.zoom-1));}
    private String armyText(long n){return n>=100000?n/1000+"k":Long.toString(n);}
    private void drawCounters(){
        if(hud==null||hud.game==null)return;
        var state=hud.game.state;
        float r=tokenRadius(),pulse=.5f+.5f*MathUtils.sin(hud.time()*5.5f),textScale=r/13f;
        counter.getData().setScale(textScale);
        begin();
        int a=hud.arrowFrom(),b=hud.arrowTo();
        if(a>=0&&b>=0)arrow(board.regions.get(a),board.regions.get(b),r,hud.actionColor());
        for(int i=0;i<board.regions.size();i++){
            Board.Region region=board.regions.get(i);float x=camera.screenX(region.anchor.x),y=camera.screenY(region.anchor.y);
            if(!camera.inside(x,y))continue;
            Color owner=Color.valueOf(state.color(state.owners[i]));
            float w=Math.max(2*r,width(counter,armyText(state.armies[i]))+r*.9f);
            GameHud.Role role=hud.role(i);
            switch(role){
                case SOURCE -> {round(x-w/2-7-2*pulse,y-r-7-2*pulse,w+14+4*pulse,2*r+14+4*pulse,r+7+2*pulse,alpha(WHITE,.25f));round(x-w/2-4,y-r-4,w+8,2*r+8,r+4,WHITE);}
                case TARGET -> round(x-w/2-4,y-r-4,w+8,2*r+8,r+4,hud.actionColor());
                case OPTION -> round(x-w/2-4-3*pulse,y-r-4-3*pulse,w+8+6*pulse,2*r+8+6*pulse,r+4+3*pulse,alpha(hud.actionColor(),.45f+.45f*pulse));
                case HINT -> round(x-w/2-3,y-r-3,w+6,2*r+6,r+3,alpha(GOLD,.7f));
                default -> {}
            }
            // A raised game piece: drop shadow, dark rim, bevel in the owner's colour and a lighter face.
            round(x-w/2+1,y-r-3,w,2*r,r,alpha(Color.BLACK,.45f));
            round(x-w/2-1.5f,y-r-1.5f,w+3,2*r+3,r+1.5f,role==GameHud.Role.DIM?alpha(INK,.75f):INK);
            Color bevel=new Color(owner).mul(.68f,.68f,.68f,1),face=new Color(owner).lerp(WHITE,.1f);
            if(role==GameHud.Role.DIM){bevel.lerp(OCEAN,hud.dimStrength()*.9f);face.lerp(OCEAN,hud.dimStrength()*.9f);}
            round(x-w/2,y-r,w,2*r,r,bevel);
            round(x-w/2+r*.17f,y-r+r*.17f,w-r*.34f,2*r-r*.34f,r*.83f,face);
        }
        shapes.end();batch.begin();
        for(int i=0;i<board.regions.size();i++){
            Board.Region region=board.regions.get(i);float x=camera.screenX(region.anchor.x),y=camera.screenY(region.anchor.y);
            if(!camera.inside(x,y))continue;
            centered(counter,armyText(state.armies[i]),x,y+counter.getCapHeight()/2,hud.role(i)==GameHud.Role.DIM?alpha(WHITE,.6f):Color.WHITE);
        }
        // Floating +/- numbers confirm deployments, losses and moves where they happen.
        counter.getData().setScale(textScale*1.15f);
        for(GameHud.Floater f:hud.floaters()){
            Board.Region region=board.regions.get(f.region());float age=hud.time()-f.born();
            float x=camera.screenX(region.anchor.x),y=camera.screenY(region.anchor.y)+r+10+age*34;
            centered(counter,f.text(),x,y,alpha(f.color(),MathUtils.clamp(1.6f-age*1.2f,0,1)));
        }
        counter.getData().setScale(1);batch.end();
    }
    private void arrow(Board.Region from,Board.Region to,float r,Color c){
        if(Math.abs(from.anchor.x-to.anchor.x)>900)return; // Pacific wrap: the sea lane already shows the route.
        float x1=camera.screenX(from.anchor.x),y1=camera.screenY(from.anchor.y),x2=camera.screenX(to.anchor.x),y2=camera.screenY(to.anchor.y);
        float dx=x2-x1,dy=y2-y1,len=(float)Math.sqrt(dx*dx+dy*dy);if(len<2*r+14)return;
        float ux=dx/len,uy=dy/len,sx=x1+ux*(r+4),sy=y1+uy*(r+4),ex=x2-ux*(r+6),ey=y2-uy*(r+6),head=Math.min(18,len/3);
        float bx=ex-ux*head,by=ey-uy*head,px=-uy*head*.6f,py=ux*head*.6f;
        line(sx,sy,bx,by,7.5f,alpha(INK,.85f));line(sx,sy,bx,by,4.5f,c);
        shapes.setColor(alpha(INK,.85f));shapes.triangle(ex+ux*2.5f,ey+uy*2.5f,bx+px*1.3f-ux*2,by+py*1.3f-uy*2,bx-px*1.3f-ux*2,by-py*1.3f-uy*2);
        shapes.setColor(c);shapes.triangle(ex,ey,bx+px,by+py,bx-px,by-py);
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
            float x=camera.screenX(r.anchor.x), y=camera.screenY(r.anchor.y)+(hud!=null&&hud.game!=null?tokenRadius()+15:6);
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
            boolean dim=hud.role(board.regions.indexOf(r))==GameHud.Role.DIM;
            centered(small,label,x,y,coastal?(dim?MUTED:WHITE):dim?alpha(INK,.55f):INK);
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
    private Board.Region pick(float x,float y) {
        if(!camera.inside(x,y))return null;
        Vector2 p=camera.world(x,y);
        // Small-island anchors receive a screen-space target so PEI and Hawaii
        // remain selectable even at the overview scale.
        float reach=hud!=null&&hud.game!=null?tokenRadius()+3:7;
        Board.Region nearest=null;float distance=reach*reach;
        for(Board.Region r:board.regions)if(visible(r)) {
            float dx=camera.screenX(r.anchor.x)-x,dy=camera.screenY(r.anchor.y)-y,d=dx*dx+dy*dy;
            if(d<distance){nearest=r;distance=d;}
        }
        if(nearest!=null)return nearest;
        for(Board.Region r:board.regions)if(visible(r)&&r.contains(p.x,p.y))return r;
        return null;
    }
    private class Controls extends InputAdapter {
        private final Vector2[] fingers={new Vector2(),new Vector2()};
        private final boolean[] down={false,false};
        private boolean zoomGesture;
        private float pinchDistance;

        private Vector2 point(int x,int y){return viewport.unproject(new Vector2(x,y));}
        @Override public boolean touchDown(int x,int y,int pointer,int button) {
            Vector2 p=point(x,y);
            if(pointer<2){fingers[pointer].set(p);down[pointer]=true;if(down[0]&&down[1]){zoomGesture=true;pinchDistance=fingers[0].dst(fingers[1]);return true;}}
            downX=lastX=p.x;downY=lastY=p.y;dragDistance=0;
            dragging=null;
            for(int i=sliders.size()-1;i>=0;i--)if(sliders.get(i).rect().contains(p)){dragging=sliders.get(i);dragging.update(p.x);break;}
            return true;
        }
        @Override public boolean touchDragged(int x,int y,int pointer) {
            Vector2 p=point(x,y);
            if(pointer<2)fingers[pointer].set(p);
            if(down[0]&&down[1]){float distance=fingers[0].dst(fingers[1]);if(!hud.blocksBoard()&&pinchDistance>1)camera.zoomAt(distance/pinchDistance,(fingers[0].x+fingers[1].x)/2,(fingers[0].y+fingers[1].y)/2);pinchDistance=distance;return true;}
            if(zoomGesture)return true;
            if(dragging!=null){dragging.update(p.x);return true;}
            dragDistance=Math.max(dragDistance,Vector2.dst(downX,downY,p.x,p.y));
            if(!hud.blocksBoard()&&camera.inside(downX,downY)&&dragDistance>4)camera.pan(p.x-lastX,p.y-lastY);
            lastX=p.x;lastY=p.y;return true;
        }
        @Override public boolean touchUp(int x,int y,int pointer,int button) {
            if(pointer<2)down[pointer]=false;
            if(zoomGesture){if(!down[0]&&!down[1])zoomGesture=false;return true;}
            if(dragging!=null){dragging=null;return true;}
            if(dragDistance>4)return true;Vector2 p=point(x,y);
            for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).rect.contains(p)){hits.get(i).action.run();return true;}
            if(!hud.blocksBoard()&&camera.inside(p.x,p.y)){Board.Region tapped=pick(p.x,p.y);if(tapped!=null)hud.tap(board.regions.indexOf(tapped));else hud.tapOcean();}
            return true;
        }
        @Override public boolean keyTyped(char character){return hud.typed(character);}
        @Override public boolean scrolled(float dx,float dy) {
            if(!hud.blocksBoard()&&camera.inside(mouse.x,mouse.y))camera.zoomAt((float)Math.pow(1.18,-dy),mouse.x,mouse.y);return true;
        }
        @Override public boolean keyDown(int key) {
            if(key==Input.Keys.ESCAPE||key==Input.Keys.BACK){hud.back();return true;}
            if(hud.editKey(key))return true;
            if(hud.blocksBoard())return true;
            if(hud.key(key))return true;
            switch(key){
                case Input.Keys.L -> showLabels=!showLabels;
                case Input.Keys.S -> showRoutes=!showRoutes;
                case Input.Keys.HOME -> camera.reset();
                case Input.Keys.PLUS,Input.Keys.EQUALS -> camera.zoomAt(1.25f,800,606);
                case Input.Keys.MINUS -> camera.zoomAt(.8f,800,606);
                default -> {return false;}
            }return true;
        }
    }

    @Override public void pause(){if(hud!=null)hud.pause();}
    @Override public void dispose(){hud.dispose();gameFont.dispose();counter.dispose();big.dispose();shapes.dispose();batch.dispose();pixel.dispose();small.dispose();body.dispose();heading.dispose();title.dispose();}
}
