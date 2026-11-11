package com.supremacy.board;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * An original nautical atlas, rendered in libGDX from geographic polygons. Everything is laid out
 * in density-independent UI units: one unit is about one Android dp, so text and touch targets keep
 * their physical size from phones to desktop windows.
 */
public final class AtlasApp extends ApplicationAdapter {
    static final Color INK=Color.valueOf("0D1C29"), PANEL=Color.valueOf("132736"),
        OCEAN=Color.valueOf("162E3C"), WHITE=Color.valueOf("E8E6DB"), MUTED=Color.valueOf("8FA8B4"),
        GOLD=Color.valueOf("E4C484"), GRID=Color.valueOf("213E4C"), BORDER=Color.valueOf("0F2230");
    private static final float[] TEXT_SIZES={.9f,1f,1.15f,1.3f};
    final AtlasCamera camera=new AtlasCamera();
    private final OrthographicCamera view=new OrthographicCamera();
    final List<Hit> hits=new ArrayList<>();
    private final Map<Board.Connection,List<Vector2>> lanes=new HashMap<>();
    private final GlyphLayout layout=new GlyphLayout();
    final Vector2 mouse=new Vector2();
    /** Pixels per UI unit, and the screen size in UI units. */
    float ui=1, W=1600, H=1000;
    Board board;
    Board.Region hovered;
    ShapeRenderer shapes;
    SpriteBatch batch;
    MapMesh mesh;
    private Texture pixel;
    private static final Map<String,String> OVERVIEW_LABELS=Map.ofEntries(
        Map.entry("British Columbia","B.C."),Map.entry("Alberta","AB"),Map.entry("Saskatchewan","SK"),
        Map.entry("Manitoba","MB"),Map.entry("Ontario","ON"),Map.entry("Quebec","QC"),
        Map.entry("Northwest Territories","N.W.T."),Map.entry("Newfoundland and Labrador","N.L."),
        Map.entry("Western Australia","W.A."),Map.entry("Northern Territory","N.T."),
        Map.entry("South Australia","S.A."),Map.entry("Queensland","QLD"),
        Map.entry("New South Wales","N.S.W."),Map.entry("Victoria","VIC"),Map.entry("Tasmania","TAS"),
        Map.entry("Prince Edward Island","PEI"),Map.entry("Nova Scotia","N.S."),Map.entry("New Brunswick","N.B."));
    BitmapFont small,body,title,heading,gameFont,counter,big;
    private final List<BitmapFont> fonts=new ArrayList<>();
    private float fontUi=-1;
    GameHud hud;
    private boolean showRoutes=true,showLabels=true;
    final List<Slider> sliders=new ArrayList<>();
    private int frames;
    // Badge positions after collision avoidance, in world units, cached per scale.
    private float[] badgeX,badgeY;
    private float badgeScale=-1;
    private long badgeKey;

    /** A tappable rectangle; {@code label} lets scripted tours press controls by name. */
    record Hit(Rectangle rect,Runnable action,String label) {}
    /** A horizontal control that reports the touch position as a 0..1 fraction while dragged. */
    record Slider(Rectangle rect,java.util.function.Consumer<Float> onChange) {
        void update(float x){onChange.accept(MathUtils.clamp((x-rect.x)/rect.width,0,1));}
    }

    @Override public void create() {
        board=new Board(new InputStreamReader(Gdx.files.internal("board.json").read(),StandardCharsets.UTF_8));
        shapes=new ShapeRenderer(5000); batch=new SpriteBatch(); mesh=new MapMesh(board);
        Pixmap dot=new Pixmap(1,1,Pixmap.Format.RGBA8888);dot.setColor(Color.WHITE);dot.fill();pixel=new Texture(dot);dot.dispose();
        for (Board.Connection e:board.connections) if(e.kind()==Board.Kind.SEA) lanes.put(e,curve(e));
        measure(Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
        hud=new GameHud(this);
        Gdx.input.setInputProcessor(controls);
        Gdx.input.setCatchKey(Input.Keys.BACK,true);
        // Redraw on input, animation or bot playback only, instead of burning battery while idle.
        Gdx.graphics.setContinuousRendering(System.getProperty("supremacy.screenshot")!=null);
    }

    /** Picks the UI scale for a window size: device density on phones, window size on desktop. */
    private void measure(int width,int height) {
        float density;
        String forced=System.getProperty("supremacy.density");
        if(forced!=null)density=Float.parseFloat(forced);
        else if(Gdx.app.getType()==Application.ApplicationType.Android)density=Gdx.graphics.getDensity();
        else density=Math.max(1f,Math.min(width/1280f,height/800f));
        density*=TEXT_SIZES[textSize()];
        // Never lay out on fewer than 340×640 units: very small screens shrink slightly instead.
        ui=Math.max(.5f,Math.min(density,Math.min(height/340f,width/640f)));
        W=width/ui;H=height/ui;
        view.setToOrtho(false,W,H);view.update();
        if(Math.abs(ui-fontUi)>.01f)makeFonts();
    }
    int textSize(){
        try{return MathUtils.clamp(Gdx.app.getPreferences("supremacy-options").getInteger("textSize",1),0,TEXT_SIZES.length-1);}
        catch(RuntimeException e){return 1;}
    }
    void applyTextSize(){measure(Gdx.graphics.getWidth(),Gdx.graphics.getHeight());badgeScale=-1;}

    private void makeFonts() {
        for(BitmapFont f:fonts)f.dispose();
        fonts.clear();fontUi=ui;
        small=font("NotoSans-Regular.ttf",12); body=font("NotoSans-Regular.ttf",14);
        title=font("NotoSerif-Regular.ttf",30); heading=font("NotoSerif-Regular.ttf",21);
        gameFont=font("NotoSans-Regular.ttf",16); big=font("NotoSans-Regular.ttf",34);
        counter=outlined("NotoSans-Regular.ttf",14);
    }
    /** Fonts are rasterised at the real pixel size and drawn at 1/ui scale, so text stays sharp. */
    private BitmapFont font(String name,int size) {
        FreeTypeFontGenerator g=new FreeTypeFontGenerator(Gdx.files.internal("fonts/"+name));
        var p=new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size=Math.max(6,Math.round(size*ui)); p.characters+="—−×·…’→‹›•÷▲▼✓"; p.minFilter=Texture.TextureFilter.Linear;p.magFilter=Texture.TextureFilter.Linear;
        BitmapFont f=g.generateFont(p);g.dispose();prepare(f);return f;
    }
    /** Army numbers carry a dark outline so they read on every player colour and zoom level. */
    private BitmapFont outlined(String name,int size) {
        FreeTypeFontGenerator g=new FreeTypeFontGenerator(Gdx.files.internal("fonts/"+name));
        var p=new FreeTypeFontGenerator.FreeTypeFontParameter();
        // Rendered larger than needed: badges grow with zoom and are scaled down otherwise.
        p.size=Math.max(8,Math.round(size*ui*1.6f)); p.characters="0123456789k+−-!"; p.borderWidth=Math.max(1.5f,1.6f*ui); p.borderColor=new Color(0.03f,0.07f,0.1f,0.9f);
        p.minFilter=Texture.TextureFilter.Linear;p.magFilter=Texture.TextureFilter.Linear;
        BitmapFont f=g.generateFont(p);g.dispose();f.setUseIntegerPositions(false);f.getData().setScale(1/(ui*1.6f));fonts.add(f);return f;
    }
    private void prepare(BitmapFont f){f.setUseIntegerPositions(false);f.getData().setScale(1/ui);fonts.add(f);}
    /** Sets a font's size relative to its design size (1 = normal). */
    void scaleFont(BitmapFont f,float s){f.getData().setScale(s/(f==counter?ui*1.6f:ui));}

    @Override public void resize(int width,int height) {
        measure(width,height);badgeScale=-1;
        Gdx.graphics.requestRendering();
    }
    @Override public void render() {
        float dt=Math.min(.1f,Gdx.graphics.getDeltaTime());
        ScreenUtils.clear(INK);
        HdpiUtils.glViewport(0,0,Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
        shapes.setProjectionMatrix(view.combined);batch.setProjectionMatrix(view.combined);
        mouse.set(Gdx.input.getX()/ui,H-Gdx.input.getY()/ui);
        hits.clear();sliders.clear();
        hud.layoutMap();
        if(camera.update(dt))Gdx.graphics.requestRendering();
        hovered=hud.blocksBoard()||!camera.visible(mouse.x,mouse.y,0)?null:pick(mouse.x,mouse.y);
        Gdx.gl.glEnable(GL20.GL_BLEND);Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,GL20.GL_ONE_MINUS_SRC_ALPHA);
        drawMap(); hud.render();
        controls.longPressCheck();
        frames++;
        if(hud.animating()||controls.down())Gdx.graphics.requestRendering();
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
        if(r>0){int seg=r>6?8:5;shapes.arc(x+r,y+r,r,180,90,seg);shapes.arc(x+w-r,y+r,r,270,90,seg);shapes.arc(x+w-r,y+h-r,r,0,90,seg);shapes.arc(x+r,y+h-r,r,90,90,seg);}
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
    /** Height of wrapped text, for laying out flowing panels. */
    float wrappedHeight(BitmapFont f,String value,float width){layout.setText(f,value,Color.WHITE,width,Align.left,true);return layout.height;}
    void wrap(BitmapFont f,String value,float x,float y,float width,Color color) {
        f.setColor(color);f.draw(batch,value,x,y,width,Align.left,true);
    }
    static Color alpha(Color c,float a){return new Color(c.r,c.g,c.b,a);}
    void clip(float x,float y,float w,float h) {
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor(Math.round(x*ui),Math.round(y*ui),Math.round(w*ui),Math.round(h*ui));
    }
    void unclip(){Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);}

    /** Fill colour of a territory: its owner's colour in the default view, or its continent's. */
    private Color regionColor(int i) {
        Board.Region r=board.regions.get(i);
        if(hud.game==null||hud.continentMode()){
            Color c=Color.valueOf(r.continent.color());
            return c.lerp(WHITE,Math.floorMod(r.id.hashCode(),4)*.035f);
        }
        Color owner=Color.valueOf(hud.game.state.color(hud.game.state.owners[i]));
        // Muted so the army badges stay the brightest thing on the board; neighbours alternate a little.
        return owner.lerp(OCEAN,.32f+Math.floorMod(r.id.hashCode(),3)*.03f);
    }

    private void drawMap() {
        clip(camera.x,camera.y,camera.width,camera.height); begin();
        rect(camera.x,camera.y,camera.width,camera.height,OCEAN);
        // Subtle atlas graticule and hand-drawn wave marks stay attached to the board.
        float s=camera.scale();
        for(int x=0;x<=1800;x+=150)line(camera.screenX(x),camera.screenY(0),camera.screenX(x),camera.screenY(750),.6f,GRID);
        for(int y=0;y<=750;y+=100)line(camera.screenX(0),camera.screenY(y),camera.screenX(1800),camera.screenY(y),.6f,GRID);
        Random rng=new Random(2904);
        for(int i=0;i<300;i++) {
            float x=camera.screenX(rng.nextFloat()*1800),y=camera.screenY(rng.nextFloat()*750);
            if(!camera.inside(x,y))continue;
            line(x,y,x+3*s,y-1*s,.6f,alpha(MUTED,.12f));line(x+3*s,y-1*s,x+6*s,y,.6f,alpha(MUTED,.12f));
        }
        int focus=hud.focus();
        Board.Region selected=focus<0?null:board.regions.get(focus);
        if(showRoutes)for(Board.Connection e:board.connections)if(e.kind()==Board.Kind.SEA)drawLane(e,false,selected);
        compass(camera.screenX(230),camera.screenY(150),42*Math.min(1.4f,s*1.1f));
        ship(camera.screenX(555),camera.screenY(425),Math.min(1.3f,s*1.1f));
        ship(camera.screenX(1240),camera.screenY(175),Math.min(1.3f,s*1.2f));
        shapes.end();
        // Land: baked meshes. Shadow, fill, then every border so highlights are never covered.
        mesh.begin(view.combined,camera);
        Color shadow=alpha(Color.BLACK,.3f);
        for(int i=0;i<board.regions.size();i++)mesh.fill(i,shadow,1.5f,-2.5f);
        Board.Continent spotlight=hud.highlightContinent();
        for(int i=0;i<board.regions.size();i++) {
            Board.Region r=board.regions.get(i);
            Color c=regionColor(i);
            GameHud.Role role=hud.role(i);
            if(spotlight!=null&&r.continent!=spotlight)c.lerp(OCEAN,.6f);
            else if(role==GameHud.Role.DIM)c.lerp(OCEAN,hud.dimStrength());
            else if(role==GameHud.Role.SOURCE)c.lerp(WHITE,.28f);
            else if(role==GameHud.Role.TARGET)c.lerp(WHITE,.2f);
            else if(r==hovered)c.lerp(WHITE,.14f);
            mesh.fill(i,c,0,0);
        }
        float pulse=.5f+.5f*MathUtils.sin(hud.time()*5f);
        for(int i=0;i<board.regions.size();i++)mesh.outline(i,BORDER,1f);
        for(int i=0;i<board.regions.size();i++){
            Color recap=hud.recapColor(i);
            if(recap!=null)mesh.outline(i,alpha(recap,.55f+.45f*pulse),2.5f);
            if(spotlight!=null&&board.regions.get(i).continent==spotlight)mesh.outline(i,GOLD,1.8f);
            GameHud.Role role=hud.role(i);
            if(role==GameHud.Role.SOURCE)mesh.outline(i,WHITE,2.5f);
            else if(role==GameHud.Role.TARGET)mesh.outline(i,hud.actionColor(),2.5f);
        }
        begin();
        // Highlighted lanes are drawn over land to identify their exact endpoints.
        if(showRoutes && selected!=null)for(Board.Connection e:board.links(selected))if(e.kind()==Board.Kind.SEA)drawLane(e,true,selected);
        shapes.end();
        batch.begin();
        ocean("P A C I F I C   O C E A N",190,350);
        ocean("A T L A N T I C",700,440);ocean("O C E A N",700,407);
        ocean("I N D I A N   O C E A N",1300,165);
        ocean("S O U T H   P A C I F I C",420,120);
        ocean("A R C T I C   O C E A N",900,735);
        batch.end();
        layoutBadges();
        if(showLabels){batch.begin();drawLabels(selected);batch.end();}
        drawCounters();
        unclip();
    }
    /** Badge radius grows with zoom so pieces stay legible without hiding the map. */
    float tokenRadius(){return MathUtils.clamp(8.5f+3.4f*(camera.zoom-1),8.5f,19);}
    private static String armyText(long n){return n>=100000?n/1000+"k":Long.toString(n);}
    private float badgeWidth(int i){
        float r=tokenRadius();
        if(hud.game==null)return 2*r;
        scaleFont(counter,r/9.5f);
        float w=Math.max(2*r,width(counter,armyText(hud.game.state.armies[i]))+r*.9f);
        scaleFont(counter,1);return w;
    }
    /**
     * Spreads overlapping army badges apart (in world units, so the layout pans with the map) and
     * remembers the result until the zoom or the army digits change.
     */
    private void layoutBadges(){
        int n=board.regions.size();
        if(badgeX==null){badgeX=new float[n];badgeY=new float[n];}
        float s=camera.scale();
        long key=0;
        if(hud.game!=null)for(int i=0;i<n;i++)key=key*31+armyText(hud.game.state.armies[i]).length();
        if(Math.abs(s-badgeScale)<badgeScale*.02f&&key==badgeKey)return;
        badgeScale=s;badgeKey=key;
        float[] half=new float[n];
        for(int i=0;i<n;i++){Board.Region r=board.regions.get(i);badgeX[i]=r.anchor.x;badgeY[i]=r.anchor.y;half[i]=(badgeWidth(i)/2+1.5f)/s;}
        float rh=(tokenRadius()+1.5f)/s;
        for(int iter=0;iter<40;iter++){
            boolean moved=false;
            for(int a=0;a<n;a++)for(int b=a+1;b<n;b++){
                float dx=badgeX[b]-badgeX[a],dy=badgeY[b]-badgeY[a];
                float ox=half[a]+half[b]-Math.abs(dx),oy=2*rh-Math.abs(dy);
                if(ox<=0||oy<=0)continue;
                moved=true;
                // Separate along the axis that needs the smaller push.
                if(ox*.8f<oy){float push=ox/2*(dx>=0?1:-1);if(dx==0)push=ox/2*(a<b?-1:1);badgeX[a]-=push;badgeX[b]+=push;}
                else{float push=oy/2*(dy>=0?1:-1);if(dy==0)push=oy/2*(a<b?-1:1);badgeY[a]-=push;badgeY[b]+=push;}
            }
            // A gentle pull back to the anchors keeps badges close to their territories.
            for(int i=0;i<n;i++){Board.Region r=board.regions.get(i);badgeX[i]+=(r.anchor.x-badgeX[i])*.04f;badgeY[i]+=(r.anchor.y-badgeY[i])*.04f;}
            if(!moved)break;
        }
    }
    float badgeScreenX(int i){return camera.screenX(badgeX==null?board.regions.get(i).anchor.x:badgeX[i]);}
    float badgeScreenY(int i){return camera.screenY(badgeY==null?board.regions.get(i).anchor.y:badgeY[i]);}

    private void drawCounters(){
        if(hud==null||hud.game==null)return;
        var state=hud.game.state;
        float r=tokenRadius(),pulse=.5f+.5f*MathUtils.sin(hud.time()*5.5f),textScale=r/9.5f;
        begin();
        // Leader lines join displaced badges to their territory.
        for(int i=0;i<board.regions.size();i++){
            Board.Region region=board.regions.get(i);
            float ax=camera.screenX(region.anchor.x),ay=camera.screenY(region.anchor.y),x=badgeScreenX(i),y=badgeScreenY(i);
            if(Vector2.dst(ax,ay,x,y)>r*.7f){line(ax,ay,x,y,1.4f,alpha(INK,.85f));shapes.setColor(INK);shapes.circle(ax,ay,2.6f,10);shapes.setColor(Color.valueOf(state.color(state.owners[i])));shapes.circle(ax,ay,1.6f,10);}
        }
        int a=hud.arrowFrom(),b=hud.arrowTo();
        if(a>=0&&b>=0)arrow(a,b,r,hud.arrowColor());
        scaleFont(counter,textScale);
        for(int i=0;i<board.regions.size();i++){
            float x=badgeScreenX(i),y=badgeScreenY(i);
            if(!camera.inside(x,y))continue;
            Color owner=Color.valueOf(state.color(state.owners[i]));
            float w=Math.max(2*r,width(counter,armyText(state.armies[i]))+r*.9f);
            GameHud.Role role=hud.role(i);
            switch(role){
                case SOURCE -> {round(x-w/2-6-2*pulse,y-r-6-2*pulse,w+12+4*pulse,2*r+12+4*pulse,r+6+2*pulse,alpha(WHITE,.25f));round(x-w/2-3.5f,y-r-3.5f,w+7,2*r+7,r+3.5f,WHITE);}
                case TARGET -> round(x-w/2-3.5f,y-r-3.5f,w+7,2*r+7,r+3.5f,hud.actionColor());
                case OPTION -> round(x-w/2-3.5f-3*pulse,y-r-3.5f-3*pulse,w+7+6*pulse,2*r+7+6*pulse,r+3.5f+3*pulse,alpha(hud.actionColor(),.45f+.45f*pulse));
                case HINT -> round(x-w/2-3,y-r-3,w+6,2*r+6,r+3,alpha(GOLD,.75f));
                default -> {}
            }
            // A raised game piece: drop shadow, dark rim, bevel in the owner's colour and a lighter face.
            boolean dim=role==GameHud.Role.DIM;
            round(x-w/2+1,y-r-2.5f,w,2*r,r,alpha(Color.BLACK,.45f));
            round(x-w/2-1.2f,y-r-1.2f,w+2.4f,2*r+2.4f,r+1.2f,dim?alpha(INK,.75f):INK);
            Color bevel=new Color(owner).mul(.68f,.68f,.68f,1),face=new Color(owner).lerp(WHITE,.1f);
            if(dim){bevel.lerp(OCEAN,hud.dimStrength()*.9f);face.lerp(OCEAN,hud.dimStrength()*.9f);}
            round(x-w/2,y-r,w,2*r,r,bevel);
            round(x-w/2+r*.17f,y-r+r*.17f,w-r*.34f,2*r-r*.34f,r*.83f,face);
            // Threat marker: a neighbouring enemy stack outnumbers this territory.
            if(hud.threatened(i)){
                float tx=x+w/2-1,ty=y+r-1,ts=Math.max(6,r*.62f);
                shapes.setColor(INK);shapes.triangle(tx-ts-1.5f,ty-ts*.8f-1.2f,tx+ts+1.5f,ty-ts*.8f-1.2f,tx,ty+ts*.95f+1.5f);
                shapes.setColor(GameHud.RED);shapes.triangle(tx-ts,ty-ts*.8f,tx+ts,ty-ts*.8f,tx,ty+ts*.95f);
            }
        }
        shapes.end();batch.begin();
        for(int i=0;i<board.regions.size();i++){
            float x=badgeScreenX(i),y=badgeScreenY(i);
            if(!camera.inside(x,y))continue;
            centered(counter,armyText(state.armies[i]),x,y+counter.getCapHeight()/2,hud.role(i)==GameHud.Role.DIM?alpha(WHITE,.6f):Color.WHITE);
            if(hud.threatened(i)){
                float tx=x+badgeWidth(i)/2-1,ty=y+r-1;
                scaleFont(counter,Math.max(.62f,r*.62f/9.5f));
                centered(counter,"!",tx,ty+counter.getCapHeight()*.55f,Color.WHITE);
                scaleFont(counter,textScale);
            }
        }
        // Floating +/- numbers confirm deployments, losses and moves where they happen.
        scaleFont(counter,Math.max(1.05f,textScale*1.1f));
        for(GameHud.Floater f:hud.floaters()){
            float age=hud.time()-f.born;
            float x=badgeScreenX(f.region),y=badgeScreenY(f.region)+r+8+age*30;
            centered(counter,f.text(),x,y,alpha(f.color,MathUtils.clamp(1.6f-age*1.2f,0,1)));
        }
        scaleFont(counter,1);batch.end();
    }
    private void arrow(int fromRegion,int toRegion,float r,Color c){
        Board.Region from=board.regions.get(fromRegion),to=board.regions.get(toRegion);
        if(Math.abs(from.anchor.x-to.anchor.x)>900)return; // Pacific wrap: the sea lane already shows the route.
        float x1=badgeScreenX(fromRegion),y1=badgeScreenY(fromRegion),x2=badgeScreenX(toRegion),y2=badgeScreenY(toRegion);
        float dx=x2-x1,dy=y2-y1,len=(float)Math.sqrt(dx*dx+dy*dy);if(len<2*r+10)return;
        float ux=dx/len,uy=dy/len,sx=x1+ux*(r+3),sy=y1+uy*(r+3),ex=x2-ux*(r+5),ey=y2-uy*(r+5),head=Math.min(15,len/3);
        float bx=ex-ux*head,by=ey-uy*head,px=-uy*head*.6f,py=ux*head*.6f;
        line(sx,sy,bx,by,6.5f,alpha(INK,.85f));line(sx,sy,bx,by,3.8f,c);
        shapes.setColor(alpha(INK,.85f));shapes.triangle(ex+ux*2.5f,ey+uy*2.5f,bx+px*1.3f-ux*2,by+py*1.3f-uy*2,bx-px*1.3f-ux*2,by-py*1.3f-uy*2);
        shapes.setColor(c);shapes.triangle(ex,ey,bx+px,by+py,bx-px,by-py);
    }
    private void ocean(String name,float x,float y){
        float s=camera.scale();
        scaleFont(small,MathUtils.clamp(s*1.3f,.75f,1.25f));
        centered(small,name,camera.screenX(x),camera.screenY(y),alpha(MUTED,.42f));
        scaleFont(small,1);
    }
    private void drawLabels(Board.Region selected) {
        List<Rectangle> occupied=new ArrayList<>();
        float r=tokenRadius();
        // Badges claim their space first so names never cover army numbers.
        if(hud.game!=null)for(int i=0;i<board.regions.size();i++){float w=badgeWidth(i);occupied.add(new Rectangle(badgeScreenX(i)-w/2,badgeScreenY(i)-r,w,2*r));}
        List<Integer> order=new ArrayList<>();
        for(int i=0;i<board.regions.size();i++)order.add(i);
        order.sort(Comparator.comparingInt(i->board.regions.get(i)==selected?-2:board.regions.get(i)==hovered?-1:0));
        float scale=camera.zoom>2.2f?1:.9f;
        scaleFont(small,scale);
        for(int i:order) {
            Board.Region region=board.regions.get(i);
            float x=hud.game!=null?badgeScreenX(i):camera.screenX(region.anchor.x);
            float y=hud.game!=null?badgeScreenY(i)-r-3:camera.screenY(region.anchor.y)+6;
            String label=camera.zoom<2f?OVERVIEW_LABELS.getOrDefault(region.name,region.label):region.label;
            layout.setText(small,label);
            float w=layout.width,h=layout.height;
            Rectangle bounds=new Rectangle(x-w/2-3,y-h-3,w+6,h+6);
            if(!camera.inside(x,y))continue;
            boolean collision=occupied.stream().anyMatch(b->b.overlaps(bounds));
            if(collision && region!=selected&&region!=hovered)continue;
            occupied.add(bounds);
            boolean dim=hud.role(i)==GameHud.Role.DIM;
            // Every label sits on a small dark plate so it reads on any territory colour.
            batch.setColor(alpha(INK,dim?.55f:.72f));batch.draw(pixel,bounds.x,bounds.y,bounds.width,bounds.height);batch.setColor(Color.WHITE);
            centered(small,label,x,y,dim?MUTED:WHITE);
        }
        scaleFont(small,1);
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
    private void drawLane(Board.Connection edge,boolean highlight,Board.Region selected) {
        List<Vector2> path=lanes.get(edge);
        Color c=alpha(GOLD,highlight?.95f:selected==null?.5f:.13f);
        float length=0;
        for(int i=1;i<path.size();i++) {
            Vector2 a=path.get(i-1),b=path.get(i);
            length+=a.dst(b)*camera.scale();
            float ax=wrapX(a.x),bx=wrapX(b.x);
            if(Math.abs(ax-bx)>900)continue;
            if(length%11<6.5f)line(camera.screenX(ax),camera.screenY(a.y),camera.screenX(bx),camera.screenY(b.y),highlight?2:1.1f,c);
        }
        if(highlight)for(Board.Region r:List.of(edge.a(),edge.b()))ring(camera.screenX(r.anchor.x),camera.screenY(r.anchor.y),6,1.2f,GOLD);
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
    /** The territory under a screen point: the nearest badge within a finger's reach, else the land. */
    Board.Region pick(float x,float y) {
        if(!camera.inside(x,y))return null;
        Vector2 p=camera.world(x,y);
        float reach=hud!=null&&hud.game!=null?Math.max(tokenRadius()+4,Gdx.app.getType()==Application.ApplicationType.Android?20:12):8;
        Board.Region nearest=null;float distance=reach*reach;
        for(int i=0;i<board.regions.size();i++) {
            Board.Region r=board.regions.get(i);
            float bx=hud!=null&&hud.game!=null?badgeScreenX(i):camera.screenX(r.anchor.x),by=hud!=null&&hud.game!=null?badgeScreenY(i):camera.screenY(r.anchor.y);
            float dx=bx-x,dy=by-y,d=dx*dx+dy*dy;
            if(d<distance){nearest=r;distance=d;}
        }
        for(Board.Region r:board.regions)if(r.contains(p.x,p.y)){
            // Inside a territory but closer to a neighbour's badge: prefer the land actually touched
            // unless the finger is clearly on that badge.
            if(nearest==null||distance>(tokenRadius()+2)*(tokenRadius()+2))return r;
            return nearest;
        }
        return nearest;
    }

    /** Presses the control drawn last frame with this label (scripted tours and tests). */
    boolean press(String label){
        for(int i=hits.size()-1;i>=0;i--)if(label.equals(hits.get(i).label)){hits.get(i).action.run();Gdx.graphics.requestRendering();return true;}
        return false;
    }
    List<String> labels(){List<String> l=new ArrayList<>();for(Hit h:hits)if(h.label!=null)l.add(h.label);return l;}
    /** Taps a territory as a player would (scripted tours). */
    void tapRegion(int i){
        Board.Region r=board.regions.get(i);
        if(!camera.visible(badgeScreenX(i),badgeScreenY(i),0))camera.flyTo(r.anchor.x,r.anchor.y,camera.zoom);
        hud.tap(i);
    }
    /** Where the last touch went down, for pressed-button feedback. */
    final Vector2 press=new Vector2(-1,-1);
    /** Hover tooltips wait until a real pointer has moved (not on touch screens or before input). */
    boolean pointerMoved;
    boolean pressing(Rectangle r){return controls.down()&&!controls.dragging()&&r.contains(press);}

    private final Controls controls=new Controls();
    private class Controls extends InputAdapter {
        private final Vector2[] fingers={new Vector2(),new Vector2()};
        private final boolean[] down={false,false};
        private boolean zoomGesture,panning,longFired;
        private float pinchDistance,downX,downY,lastX,lastY,dragDistance,downAt,vx,vy,lastMoveAt;
        private float lastTapAt=-9,lastTapX,lastTapY;
        private Slider dragging;

        Controls(){}
        boolean down(){return down[0]||down[1];}
        boolean dragging(){return panning||zoomGesture||dragging!=null;}
        private float now(){return hud==null?0:hud.time();}
        private Vector2 point(int x,int y){return new Vector2(x/ui,H-y/ui);}
        @Override public boolean touchDown(int x,int y,int pointer,int button) {
            Vector2 p=point(x,y);
            Gdx.graphics.requestRendering();
            if(pointer<2){fingers[pointer].set(p);down[pointer]=true;if(down[0]&&down[1]){zoomGesture=true;dragging=null;pinchDistance=fingers[0].dst(fingers[1]);return true;}}
            if(pointer>0)return true;
            downX=lastX=p.x;downY=lastY=p.y;dragDistance=0;downAt=now();longFired=false;panning=false;vx=vy=0;
            press.set(p);
            camera.stop();
            dragging=null;
            for(int i=sliders.size()-1;i>=0;i--)if(sliders.get(i).rect().contains(p)){dragging=sliders.get(i);dragging.update(p.x);break;}
            return true;
        }
        @Override public boolean touchDragged(int x,int y,int pointer) {
            Vector2 p=point(x,y);
            if(pointer<2)fingers[pointer].set(p);
            if(down[0]&&down[1]){float distance=fingers[0].dst(fingers[1]);if(!hud.blocksBoard()&&pinchDistance>1)camera.zoomAt(distance/pinchDistance,(fingers[0].x+fingers[1].x)/2,(fingers[0].y+fingers[1].y)/2);pinchDistance=distance;return true;}
            if(zoomGesture||pointer>0)return true;
            if(dragging!=null){dragging.update(p.x);return true;}
            dragDistance=Math.max(dragDistance,Vector2.dst(downX,downY,p.x,p.y));
            if(!hud.blocksBoard()&&camera.inside(downX,downY)&&!onHit(downX,downY)&&dragDistance>8){
                panning=true;camera.pan(p.x-lastX,p.y-lastY);
                float dt=Math.max(.008f,now()-lastMoveAt);
                vx=vx*.4f+(p.x-lastX)/dt*.6f;vy=vy*.4f+(p.y-lastY)/dt*.6f;
            }
            lastMoveAt=now();lastX=p.x;lastY=p.y;return true;
        }
        private boolean onHit(float x,float y){for(Hit h:hits)if(h.rect.contains(x,y))return true;return false;}
        @Override public boolean touchUp(int x,int y,int pointer,int button) {
            if(pointer<2)down[pointer]=false;
            Gdx.graphics.requestRendering();
            if(zoomGesture){if(!down[0]&&!down[1])zoomGesture=false;return true;}
            if(pointer>0)return true;
            if(dragging!=null){dragging=null;return true;}
            if(panning){panning=false;if(now()-lastMoveAt<.08f)camera.fling(vx,vy);return true;}
            if(longFired||dragDistance>8)return true;
            Vector2 p=point(x,y);
            for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).rect.contains(p)&&hits.get(i).rect.contains(downX,downY)){hud.haptic(8);hits.get(i).action.run();return true;}
            if(!hud.blocksBoard()&&camera.inside(p.x,p.y)){
                Board.Region tapped=pick(p.x,p.y);
                boolean consumed=tapped!=null&&hud.tap(board.regions.indexOf(tapped));
                if(!consumed&&tapped==null)hud.tapOcean();
                // Double-tap zooms in, unless the first tap was a game move.
                if(!consumed&&now()-lastTapAt<.32f&&Vector2.dst(p.x,p.y,lastTapX,lastTapY)<30){
                    Vector2 w=camera.world(p.x,p.y);camera.flyTo(w.x,w.y,Math.min(AtlasCamera.MAX_ZOOM,camera.zoom*2.2f));lastTapAt=-9;
                } else {lastTapAt=consumed?-9:now();lastTapX=p.x;lastTapY=p.y;}
            }
            return true;
        }
        /** A finger held still on a territory shows its details. */
        void longPressCheck(){
            if(!down[0]||down[1]||longFired||panning||dragging!=null||dragDistance>8||now()-downAt<.5f)return;
            if(hud.blocksBoard()||!camera.inside(downX,downY)||onHit(downX,downY))return;
            Board.Region r=pick(downX,downY);
            if(r!=null){longFired=true;hud.haptic(15);hud.inspect(board.regions.indexOf(r));}
        }
        @Override public boolean keyTyped(char character){return hud.typed(character);}
        @Override public boolean scrolled(float dx,float dy) {
            if(!hud.blocksBoard()&&camera.inside(mouse.x,mouse.y))camera.zoomAt((float)Math.pow(1.18,-dy),mouse.x,mouse.y);
            Gdx.graphics.requestRendering();return true;
        }
        @Override public boolean mouseMoved(int x,int y){pointerMoved=true;Gdx.graphics.requestRendering();return false;}
        @Override public boolean keyDown(int key) {
            Gdx.graphics.requestRendering();
            if(key==Input.Keys.ESCAPE||key==Input.Keys.BACK){hud.back();return true;}
            if(hud.editKey(key))return true;
            if(hud.blocksBoard())return hud.modalKey(key);
            if(hud.key(key))return true;
            float cx=camera.x+camera.width/2,cy=camera.y+camera.height/2;
            switch(key){
                case Input.Keys.L -> showLabels=!showLabels;
                case Input.Keys.S -> showRoutes=!showRoutes;
                case Input.Keys.HOME,Input.Keys.F -> camera.animateReset();
                case Input.Keys.PLUS,Input.Keys.EQUALS -> camera.zoomAt(1.25f,cx,cy);
                case Input.Keys.MINUS -> camera.zoomAt(.8f,cx,cy);
                case Input.Keys.LEFT -> camera.pan(120,0);
                case Input.Keys.RIGHT -> camera.pan(-120,0);
                case Input.Keys.UP -> camera.pan(0,-120);
                case Input.Keys.DOWN -> camera.pan(0,120);
                default -> {return false;}
            }return true;
        }
    }

    @Override public void pause(){if(hud!=null)hud.pause();}
    // FreeType font textures are not restored automatically if Android drops the GL context.
    @Override public void resume(){fontUi=-1;measure(Gdx.graphics.getWidth(),Gdx.graphics.getHeight());badgeScale=-1;Gdx.graphics.requestRendering();}
    @Override public void dispose(){hud.dispose();for(BitmapFont f:fonts)f.dispose();shapes.dispose();batch.dispose();pixel.dispose();mesh.dispose();}
}
