#!/usr/bin/env python3
"""Rebuild the checked-in atlas from public-domain Natural Earth geography.
Usage: python tools/generate_board.py [cache-directory]
Requires tools/requirements.txt. No Python or downloads are needed to run the game.
"""
import hashlib, json, sys, urllib.request
from pathlib import Path
from shapely.geometry import shape, Polygon, MultiPolygon, box
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'tools/cache'
CACHE.mkdir(parents=True, exist_ok=True)
BASE = 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/ca96624a56bd078437bca8184e78163e5039ad19/geojson/'
SOURCE_HASHES={'countries':'3e458fc036ad0a66411f2c1e6cac49c5d7bfb81cb1123bc513b22511a2b7fdeb',
               'provinces':'22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5'}
for name, source in [('countries', 'ne_50m_admin_0_countries'), ('provinces', 'ne_10m_admin_1_states_provinces')]:
    path = CACHE / (name + '.json')
    if not path.exists():
        urllib.request.urlretrieve(BASE + source + '.geojson', path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != SOURCE_HASHES[name]:
        raise ValueError(f'Source checksum mismatch: {path}; remove it and retry the pinned download.')
countries = json.loads((CACHE/'countries.json').read_text())['features']
provinces = json.loads((CACHE/'provinces.json').read_text())['features']
continents = [
    ('North America', 'D5AD66'), ('South America', '80B593'), ('Europe', '8CB9CE'),
    ('Africa', 'D19271'), ('Asia', 'B2A3CE'), ('Australia', 'D2BE7B'), ('Middle-East', 'C48D9C')]
territories = {}
def add(name, continent, features, description=None):
    geometries = [shape(f['geometry']) if isinstance(f, dict) else f for f in features]
    territories[name] = dict(name=name, continent=continent, geometry=unary_union(geometries).buffer(0),
                             includes=description or name)
def group(name, continent, codes):
    features = [f for f in countries if f['properties']['ADM0_A3'] in codes.split()]
    add(name, continent, features, ', '.join(f['properties']['ADMIN'] for f in features))

for f in provinces:
    p = f['properties']
    if p['admin'] == 'Canada':
        name = {'Québec':'Quebec'}.get(p['name'], p['name'])
        add(name, 'North America', [f])
for name, region in [('US-West','West'),('US-Midwest','Midwest'),('US-East','Northeast'),('US-South','South')]:
    fs = [f for f in provinces if f['properties']['admin']=='United States of America' and f['properties']['region']==region and f['properties']['name'] not in ['Alaska','Hawaii']]
    add(name, 'North America', fs, ', '.join(f['properties']['name'] for f in fs))
for name in ['Alaska','Hawaii']:
    add(name, 'North America', [f for f in provinces if f['properties']['admin']=='United States of America' and f['properties']['name']==name])
group('Central America','North America','MEX GTM BLZ HND SLV NIC CRI PAN')
group('Cuba','North America','CUB JAM HTI DOM BHS PRI TCA CYM')
group('Greenland','North America','GRL')
for name,codes in [('Brazil','BRA GUY SUR PRY'),('Argentina','ARG CHL URY FLK'),('Peru','PER ECU BOL'),('Colombia','COL'),('Venezuela','VEN TTO ABW CUW')]:
    group(name,'South America',codes)
for name,codes in [('Spain','ESP PRT AND'),('France','FRA BEL LUX MCO'),('Germany','DEU NLD CHE AUT LIE'),('Poland','POL CZE SVK HUN'),('England','GBR IRL IMN JEY GGY'),('Iceland','ISL'),('Nordics','NOR SWE FIN DNK ALD FRO'),('Italy','ITA SMR VAT SVN HRV BIH SRB KOS MNE ALB MKD GRC MLT'),('Ukraine','UKR BLR MDA ROU BGR'),('Baltics','EST LVA LTU')]:
    group(name,'Europe',codes)
for name,codes in [('Arabia','SAU YEM OMN ARE QAT BHR KWT'),('Persia','IRN IRQ AZE'),('Syria','SYR LBN ISR PSX JOR'),('Turkey','TUR GEO ARM CYP CYN')]:
    group(name,'Middle-East',codes)
for name,codes in [('Philippines','PHL PLW'),('India','IND PAK BGD NPL BTN LKA MDV KAS'),('Central Asia','KAZ AFG UZB TKM KGZ TJK'),('Mongolia','MNG'),('Thailand','THA MMR LAO KHM VNM MYS SGP'),('China','CHN TWN HKG MAC'),('Korea','KOR PRK'),('Japan','JPN'),('Indonesia','IDN BRN TLS PNG')]:
    group(name,'Asia',codes)
# Group Russian federal subjects into legible board regions, with Yakutsk and
# Irkutsk named for the familiar Risk territories requested by the author.
russian = {name:[] for name in ['Russia','Urals','Siberia','Irkutsk','Yakutsk','Far East']}
for f in provinces:
    p=f['properties']
    if p['admin']!='Russia': continue
    if p['name'] in ['Crimea','Sevastopol']: continue
    if p['region']=='Urals': name='Urals'
    elif p['region']=='Siberian': name='Irkutsk' if p['region_sub']=='East Siberian' else 'Siberia'
    elif p['name']=='Sakha (Yakutia)': name='Yakutsk'
    elif p['region']=='Far Eastern': name='Far East'
    else: name='Russia'
    russian[name].append(f)
for name, fs in russian.items():
    add(name, 'Europe' if name=='Russia' else 'Asia', fs, ', '.join(f['properties']['name'] or 'Tyumen subdivision' for f in fs))
for name in ['Western Australia','Northern Territory','South Australia','Queensland','New South Wales','Victoria']:
    names = [name]
    if name=='New South Wales': names += ['Australian Capital Territory','Jervis Bay Territory','Lord Howe Island']
    if name=='Victoria': names += ['Tasmania','Macquarie Island']
    add(name,'Australia',[f for f in provinces if f['properties']['admin']=='Australia' and f['properties']['name'] in names],', '.join(names))
group('New Zealand','Australia','NZL NFK NCL FJI VUT SLB')
for name,codes in [('Egypt','EGY LBY SDN SDS'),('Ethiopia','ETH ERI DJI SOM SOL KEN UGA TZA'),('Congo','COD COG GAB GNQ AGO RWA BDI'),('Southern Africa','ZAF NAM BWA ZWE ZMB MWI MOZ SWZ LSO'),('Madagascar','MDG COM MUS SYC'),('Algeria','DZA MAR TUN SAH'),('Nigeria','NGA BEN TGO GHA CIV'),('Mali','MLI MRT SEN GMB GNB GIN SLE LBR BFA NER CPV')]:
    group(name,'Africa',codes)
# Requested custom borders: Chad is its own territory with Cameroon, the
# Central African Republic and eastern Niger (east of 10 E); northern
# Mauritania (above 22 N) belongs to Algeria.
def country(code): return shape(next(f['geometry'] for f in countries if f['properties']['ADM0_A3']==code))
eastern_niger=country('NER').intersection(box(10,-90,180,90))
add('Chad','Africa',[country('TCD'),country('CMR'),country('CAF'),eastern_niger],
    'Chad, Cameroon, Central African Republic, eastern Niger (east of 10 E)')
territories['Mali']['geometry']=territories['Mali']['geometry'].difference(eastern_niger).buffer(0)
territories['Mali']['includes']=territories['Mali']['includes'].replace('Niger','western Niger (west of 10 E)')
mauritania=shape(next(f['geometry'] for f in countries if f['properties']['ADM0_A3']=='MRT'))
northern_mauritania=mauritania.intersection(box(-180,22,180,90))
territories['Mali']['geometry']=territories['Mali']['geometry'].difference(northern_mauritania)
territories['Mali']['includes']=territories['Mali']['includes'].replace('Mauritania','southern Mauritania (south of 22 N)')
territories['Algeria']['geometry']=unary_union([territories['Algeria']['geometry'],northern_mauritania]).buffer(0)
territories['Algeria']['includes']+=', northern Mauritania (north of 22 N)'
# Move overseas country components to their geographic neighbours, e.g. French
# Guiana into Brazil. The main land mass remains with its assigned territory.
for name in list(territories):
    g=territories[name]['geometry']
    if name=='France':
        overseas=g.difference(box(-20,30,30,65))
        territories[name]['geometry']=g.intersection(box(-20,30,30,65))
        for part in getattr(overseas,'geoms',[overseas]):
            if part.is_empty: continue
            target='Brazil' if part.centroid.y<10 and part.centroid.x<0 else 'Cuba' if part.centroid.x<0 else 'Madagascar'
            territories[target]['geometry']=unary_union([territories[target]['geometry'],part])
            territories[target]['includes'] += ', nearby French overseas islands/land'
# Remaining small islands are absorbed into the nearest regional territory.
assigned=set('CAN USA AUS RUS ATA ATF HMD SGS TCD CMR CAF NER MRT'.split())
for t in territories.values():
    for f in countries:
        if f['properties']['ADMIN'] in t['includes'].split(', '): assigned.add(f['properties']['ADM0_A3'])
for f in countries:
    if f['properties']['ADM0_A3'] in assigned: continue
    g=shape(f['geometry'])
    if g.area > 8: raise ValueError('Unassigned large country: '+f['properties']['ADMIN'])
    name=min(territories,key=lambda n: territories[n]['geometry'].distance(g))
    territories[name]['geometry']=unary_union([territories[name]['geometry'],g]).buffer(0)
    territories[name]['includes']+=', '+f['properties']['ADMIN']
# Each endpoint is an actual territory. Land edges come from shared boundaries;
# sea edges below are a deliberate, modest expansion of classic Risk routes.
edges=[]
names=list(territories)
for i,a in enumerate(names):
    for b in names[i+1:]:
        ga,gb=territories[a]['geometry'],territories[b]['geometry']
        if ga.boundary.intersection(gb.boundary).length > .05 or ga.buffer(.012).intersection(gb).area > .008:
            edges.append(dict(a=a,b=b,kind='LAND',via=[]))
# Some sources use different shoreline resolution at international borders.
def link(a,b,kind='LAND',via=None):
    global edges
    edges=[e for e in edges if {e['a'],e['b']}!={a,b}]
    edges.append(dict(a=a,b=b,kind=kind,via=via or []))
for a,b in [('Alaska','Yukon'),('Alaska','British Columbia'),('US-West','British Columbia'),('US-West','Alberta'),('US-West','Saskatchewan'),('US-Midwest','Saskatchewan'),('US-Midwest','Manitoba'),('US-Midwest','Ontario'),('US-East','Ontario'),('US-East','Quebec'),('US-East','New Brunswick'),('US-West','Central America'),('US-South','Central America'),('Russia','Urals'),('Russia','Ukraine'),('Russia','Baltics'),('Russia','Nordics'),('Russia','Central Asia'),('Russia','Turkey'),('Urals','Central Asia'),('Siberia','Central Asia'),('Siberia','China'),('Irkutsk','Mongolia'),('Irkutsk','China'),('Far East','China'),('Far East','Korea')]: link(a,b)
# Geographic waypoints route lanes through ocean rather than across land.
sea = [
('Alaska','Far East',[[-176,66],[176,66]]),
('Hawaii','US-West',[[-143,22],[-125,30]]),
('Hawaii','Japan',[[-178,18],[178,18],[150,24]]),
('Nunavut','Greenland',[[-67,72]]),
('Newfoundland and Labrador','Greenland',[[-43,56]]),
('Greenland','Iceland',[[-29,64]]),
('Iceland','England',[[-14,58]]),
('Iceland','Nordics',[[-2,68]]),
('England','France',[[-4,49]]),
('England','Nordics',[[2,58]]),
('Spain','Algeria',[[-5,35]]),
('Italy','Algeria',[[8,37]]),
('Italy','Turkey',[[27,35]]),
('US-South','Cuba',[[-83,25]]),
('Central America','Cuba',[[-87,20]]),
('Cuba','Venezuela',[[-70,17]]),
('Brazil','Mali',[[-27,-1],[-20,9]]),
('Nova Scotia','Newfoundland and Labrador',[[-56,46]]),
('Prince Edward Island','New Brunswick',[[-64,46.5]]),
('Prince Edward Island','Nova Scotia',[[-62,45.8]]),
('Arabia','Ethiopia',[[45,12]]),
('Madagascar','Southern Africa',[[40,-22]]),
('Madagascar','Ethiopia',[[51,-5]]),
('India','Arabia',[[62,17]]),
('India','Indonesia',[[88,7],[95,0]]),
('Korea','Japan',[[130,36]]),
('Japan','Far East',[[146,47]]),
('China','Philippines',[[119,21]]),
('Philippines','Indonesia',[[125,3]]),
('Thailand','Indonesia',[[103,0]]),
('Indonesia','Western Australia',[[117,-17]]),
('Indonesia','Queensland',[[145,-11]]),
('New South Wales','New Zealand',[[162,-34]]),
('Victoria','New Zealand',[[159,-47]])]
for a,b,via in sea: link(a,b,'SEA',via)
# Consistent coordinates: 5 units per degree, zero at (-180,-60).
def xy(point): return [round((point[0]+180)*5,2),round((point[1]+60)*5,2)]
anchors={
 'Alaska':(-151,64),'Yukon':(-135,64),'Northwest Territories':(-120,65),'Nunavut':(-96,69),
 'British Columbia':(-125,54),'Alberta':(-115,55),'Saskatchewan':(-106,55),'Manitoba':(-97,55),'Ontario':(-85,50),'Quebec':(-72,54),
 'Newfoundland and Labrador':(-58,53),'New Brunswick':(-66,46.6),'Nova Scotia':(-63,45),'Prince Edward Island':(-63.2,46.4),
 'US-West':(-114,39),'US-Midwest':(-94,42),'US-East':(-74,42),'US-South':(-88,34),'Central America':(-100,22),'Cuba':(-78,22),'Hawaii':(-156,20),
 'Greenland':(-42,73),'Brazil':(-52,-10),'Argentina':(-66,-36),'Peru':(-73,-13),'Colombia':(-73,4),'Venezuela':(-65,7),
 'Iceland':(-19,65),'England':(-3,55),'Nordics':(18,65),'France':(2,47),'Spain':(-4,40),'Germany':(10,50),'Poland':(20,51),'Italy':(16,42),'Ukraine':(30,49),'Baltics':(25,58),
 'Russia':(44,60),'Urals':(66,63),'Siberia':(82,57),'Irkutsk':(103,60),'Yakutsk':(128,65),'Far East':(158,61),
 'Turkey':(35,40),'Syria':(37,32),'Arabia':(46,24),'Persia':(52,33),'Central Asia':(65,43),'India':(78,23),'Mongolia':(103,47),'China':(105,34),'Thailand':(100,17),'Korea':(127,40),'Japan':(139,37),'Philippines':(123,12),'Indonesia':(119,-4),
 'Egypt':(28,23),'Algeria':(3,28),'Mali':(-3,17),'Nigeria':(7,7),'Ethiopia':(40,6),'Chad':(18.5,13),'Congo':(22,-5),'Southern Africa':(25,-23),'Madagascar':(47,-20),
 'Western Australia':(122,-26),'Northern Territory':(133,-20),'South Australia':(135,-30),'Queensland':(145,-22),'New South Wales':(147,-32),'Victoria':(144,-37),'Tasmania':(147,-43),'New Zealand':(173,-41)}
labels={'Northwest Territories':'NORTHWEST\nTERRITORIES','Newfoundland and Labrador':'NEWFOUNDLAND\n& LABRADOR','British Columbia':'BRITISH\nCOLUMBIA','Prince Edward Island':'P.E.I.','New Brunswick':'N.B.','Nova Scotia':'N.S.','Central America':'CENTRAL\nAMERICA','Central Asia':'CENTRAL ASIA','Southern Africa':'SOUTHERN\nAFRICA','Western Australia':'WESTERN\nAUSTRALIA','Northern Territory':'NORTHERN\nTERRITORY','South Australia':'SOUTH\nAUSTRALIA','New South Wales':'NEW SOUTH\nWALES','New Zealand':'NEW\nZEALAND'}
output=[]
for name,t in territories.items():
    g=t.pop('geometry').simplify(.12,preserve_topology=True)
    polys=list(g.geoms) if isinstance(g,MultiPolygon) else [g]
    rings=[]
    for poly in polys:
        if not isinstance(poly,Polygon) or poly.is_empty:continue
        # Retain small islands, while dropping subpixel coastal specks.
        if poly.area < .012 and name not in ['Prince Edward Island','Hawaii']:continue
        # Cartographic lakes are filled for a clean tabletop surface.
        rings.append([v for p in list(poly.exterior.coords)[:-1] for v in xy(p)])
    output.append(dict(**t,id=name.lower().replace(' ','-'),label=labels.get(name,name.upper()),anchor=xy(anchors[name]),polygons=rings))
for e in edges:e['via']=[xy(p) for p in e['via']]
data=dict(width=1800,height=750,continents=[dict(name=n,color=c) for n,c in continents],territories=output,connections=edges)
(ROOT/'src/main/resources/board.json').write_text(json.dumps(data,separators=(',',':'))+'\n')
lines=['# Territory groupings','', 'These are gameplay regions, not a claim about sovereignty. Tiny offshore islands are grouped geographically. Antarctica and remote subantarctic islands are omitted. Canada has all ten provinces and all three northern territories. Australia has five mainland state territories plus Northern Territory; Tasmania is merged with Victoria, and ACT belongs to New South Wales. Tanzania belongs to Ethiopia. Chad is its own territory and also includes Cameroon, the Central African Republic and Niger east of 10 E; western Niger belongs to Mali. Northern Mauritania above 22 N belongs to Algeria; the remainder belongs to Mali. Belgium belongs to France. Duplicate US-East and Baltics entries are deduplicated, and US-West covers the western US. Colombia and Philippines use their standard spellings.','', 'Russian subjects are grouped as Russia (European districts), Urals, Siberia (western), Irkutsk (eastern Siberia), Yakutsk (Sakha), and Far East. This keeps the board readable while including the requested Yakutsk and Irkutsk.','', '| Territory | Continent | Includes |','| --- | --- | --- |']
for t in output:lines.append(f"| {t['name']} | {t['continent']} | {t['includes']} |")
lines+=['','## Sea routes','',f'{len(sea)} bidirectional sea lanes. Dashed gold lines and endpoint rings distinguish them from solid land borders. Selecting a territory highlights only its routes; numbered lane tags in the inspector distinguish destinations. Pacific crossings wrap at both map edges.','']
for a,b,_ in sea:lines.append(f'- {a} ↔ {b}')
(ROOT/'docs/TERRITORIES.md').write_text('\n'.join(lines)+'\n')
print(f'{len(output)} territories, {len(edges)-len(sea)} land borders, {len(sea)} sea lanes')
