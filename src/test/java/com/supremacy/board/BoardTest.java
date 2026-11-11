package com.supremacy.board;

import com.badlogic.gdx.math.Vector2;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoardTest {
    private Board load() { return new Board(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/board.json")),StandardCharsets.UTF_8)); }
    @Test void includesRequestedRegionsAndSevenContinents() {
        Board b=load();assertEquals(72,b.regions.size());assertEquals(7,b.continents.size());
        for(String name:List.of("Prince Edward Island","Newfoundland and Labrador","Nunavut","Alaska","US-West","US-East","US-Midwest","US-South","Cuba","Hawaii","Yakutsk","Irkutsk","Colombia","Philippines","Victoria","New Zealand")) assertNotNull(b.region(name));
        assertEquals("Asia",b.region("Yakutsk").continent.name());assertEquals("Europe",b.region("Russia").continent.name());
        assertTrue(b.region("India").includes.contains("Pakistan"));assertTrue(b.region("Spain").includes.contains("Portugal"));assertTrue(b.region("Brazil").includes.contains("Guyana"));
        assertEquals(Set.of("Arabia","Turkey","Syria","Persia"),new HashSet<>(b.regions.stream().filter(r->r.continent.name().equals("Middle-East")).map(r->r.name).toList()));
    }
    @Test void adjacencyIsConnectedSymmetricAndHasNoDuplicateLinks() {
        Board b=load();Set<Board.Region> seen=new HashSet<>();Deque<Board.Region> queue=new ArrayDeque<>();queue.add(b.regions.get(0));
        while(!queue.isEmpty()){Board.Region r=queue.remove();if(seen.add(r))for(Board.Connection e:b.links(r))queue.add(e.other(r));}
        assertEquals(new HashSet<>(b.regions),seen);assertEquals(34,b.seaCount());
        Set<String> pairs=new HashSet<>();
        for(Board.Connection e:b.connections){assertTrue(b.links(e.a()).contains(e));assertTrue(b.links(e.b()).contains(e));assertNotSame(e.a(),e.b());
            var names=new ArrayList<>(List.of(e.a().name,e.b().name));Collections.sort(names);assertTrue(pairs.add(names.toString()));}
        assertTrue(b.links(b.region("India")).stream().anyMatch(e->e.other(b.region("India")).name.equals("Thailand")&&e.kind()==Board.Kind.LAND));
        assertTrue(b.links(b.region("Hawaii")).stream().allMatch(e->e.kind()==Board.Kind.SEA));
        assertTrue(b.regions.stream().noneMatch(r->r.name.equals("Tasmania")));
        assertTrue(b.region("Victoria").includes.contains("Tasmania"));
        assertTrue(b.links(b.region("Victoria")).stream().anyMatch(e->e.other(b.region("Victoria")).name.equals("New Zealand")&&e.kind()==Board.Kind.SEA));
    }
    @Test void everyPolygonTriangulatesAndHitTestsRecognizeInteriorPoints() {
        Board b=load();
        for(Board.Region r:b.regions)for(int j=0;j<r.polygons.size();j++){
            float[] p=r.polygons.get(j);short[] tris=r.triangles.get(j);assertTrue(tris.length>=3,r.name);assertEquals((p.length/2-2)*3,tris.length,r.name);
            for(float v:p)assertTrue(Float.isFinite(v));
        }
        for(String name:List.of("Brazil","Alberta","China","Yakutsk","Irkutsk","Western Australia")){
            Board.Region r=b.region(name);assertTrue(r.contains(r.anchor.x,r.anchor.y),name);
        }
        assertFalse(b.region("Brazil").contains(0,0));
    }
    @Test void customAfricanBordersAndBelgiumMatchTheRequestedAssignments() {
        Board b=load();
        assertOwner(b,"France",4.5f,50.5f);
        assertOwner(b,"Ethiopia",35,-6);
        assertOwner(b,"Victoria",146.5f,-42);
        // Chad is one territory with Cameroon, the Central African Republic and eastern Niger.
        assertOwner(b,"Chad",18,20);
        assertOwner(b,"Chad",16,12);
        assertOwner(b,"Chad",21,12);
        assertOwner(b,"Chad",12,5);
        assertOwner(b,"Chad",20,6);
        assertOwner(b,"Chad",12,17);
        assertOwner(b,"Mali",7,15);
        assertOwner(b,"Nigeria",8,9);
        assertOwner(b,"Egypt",25,20);
        assertEquals("Africa",b.region("Chad").continent.name());
        Board.Region ethiopia=b.region("Ethiopia");
        // Ethiopia is one landmass plus coastal islands; no detached inland piece remains.
        assertTrue(ethiopia.polygons.stream().filter(p->p.length>20).count()==1);
        assertTrue(b.links(b.region("Chad")).stream().noneMatch(e->e.other(b.region("Chad"))==ethiopia));
        assertOwner(b,"Algeria",-9,23);
        assertOwner(b,"Mali",-9,19);
    }
    private void assertOwner(Board board,String expected,float longitude,float latitude) {
        var owners=board.regions.stream().filter(r->r.contains((longitude+180)*5,(latitude+60)*5)).map(r->r.name).toList();
        assertEquals(List.of(expected),owners,"Assignment at "+longitude+", "+latitude);
    }
    @Test void cameraMaintainsTopDownTransformAndClampsZoomAndPan() {
        AtlasCamera c=new AtlasCamera();c.zoomAt(2,636,499);
        Vector2 before=c.world(600,530);c.zoomAt(1.25f,600,530);Vector2 after=c.world(600,530);
        assertEquals(before.x,after.x,.001);assertEquals(before.y,after.y,.001);
        assertEquals(600,c.screenX(after.x),.001);assertEquals(530,c.screenY(after.y),.001);
        c.zoomAt(999,636,499);assertEquals(AtlasCamera.MAX_ZOOM,c.zoom);c.pan(99999,-99999);assertTrue(c.centerX>=0&&c.centerX<=1800);assertTrue(c.centerY>=0&&c.centerY<=750);
        c.zoomAt(.0001f,636,499);assertEquals(1,c.zoom);c.reset();assertEquals(900,c.centerX);assertEquals(375,c.centerY);
    }
    @Test void cameraFitsTheUncoveredPartOfTheMapAndAnimatesToTargets() {
        AtlasCamera c=new AtlasCamera();c.setRect(0,0,800,360);c.setInsets(0,0,40,0);
        // The whole world fits the safe area at zoom 1 and its centre sits in the safe area's centre.
        assertEquals(Math.min(800/1800f,320/750f),c.scale(),1e-6);
        assertEquals(400,c.screenX(900),1e-3);assertEquals(160,c.screenY(375),1e-3);
        Board.Region alaska=load().region("Alaska");
        c.flyTo(alaska.anchor.x,alaska.anchor.y,4);
        for(int i=0;i<240&&c.update(1/60f);i++){}
        assertFalse(c.moving());assertEquals(4,c.zoom,1e-3);
        assertTrue(c.visible(c.screenX(alaska.anchor.x),c.screenY(alaska.anchor.y),0));
        c.fling(5000,0);for(int i=0;i<600&&c.update(1/60f);i++){}
        assertFalse(c.moving());assertTrue(c.centerX>=0&&c.centerX<=1800);
    }
}
