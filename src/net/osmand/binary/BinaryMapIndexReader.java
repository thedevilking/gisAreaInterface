package net.osmand.binary;


import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.procedure.TIntObjectProcedure;
import gnu.trove.set.hash.TIntHashSet;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import net.osmand.Collator;
import net.osmand.CollatorStringMatcher;
import net.osmand.CollatorStringMatcher.StringMatcherMode;
import net.osmand.Location;
import net.osmand.OsmAndCollator;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.StringMatcher;
import net.osmand.binary.BinaryMapAddressReaderAdapter.AddressRegion;
import net.osmand.binary.BinaryMapAddressReaderAdapter.CitiesBlock;
import net.osmand.binary.BinaryMapPoiReaderAdapter.PoiRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion;
import net.osmand.binary.BinaryMapTransportReaderAdapter.TransportIndex;
import net.osmand.binary.OsmandOdb.MapDataBlock;
import net.osmand.binary.OsmandOdb.OsmAndMapIndex.MapDataBox;
import net.osmand.binary.OsmandOdb.OsmAndMapIndex.MapEncodingRule;
import net.osmand.binary.OsmandOdb.OsmAndMapIndex.MapRootLevel;
import net.osmand.data.Amenity;
import net.osmand.data.AmenityType;
import net.osmand.data.Building;
import net.osmand.data.City;
import net.osmand.data.LatLon;
import net.osmand.data.MapObject;
import net.osmand.data.Street;
import net.osmand.data.TransportRoute;
import net.osmand.data.TransportStop;
import net.osmand.osm.edit.Way;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import android.os.Environment;

import com.example.readobffile.PointInPolygon;
import com.example.readobffile.SaveObjectInfo;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

public class BinaryMapIndexReader {
	
	public final static int TRANSPORT_STOP_ZOOM = 24;
	public static final int SHIFT_COORDINATES = 5;
	private final static Log log = PlatformUtil.getLog(BinaryMapIndexReader.class);
	
	private final RandomAccessFile raf;
	/*private*/ int version;
	/*private */long dateCreated;
	// keep them immutable inside
	/*private */ boolean basemap = false;
	/*private */List<MapIndex> mapIndexes = new ArrayList<MapIndex>();
	/*private */List<PoiRegion> poiIndexes = new ArrayList<PoiRegion>();
	/*private */List<AddressRegion> addressIndexes = new ArrayList<AddressRegion>();
	/*private */List<TransportIndex> transportIndexes = new ArrayList<TransportIndex>();
	/*private */List<RouteRegion> routingIndexes = new ArrayList<RouteRegion>();
	/*private */List<BinaryIndexPart> indexes = new ArrayList<BinaryIndexPart>();
	
	protected CodedInputStream codedIS;
	
	private final BinaryMapTransportReaderAdapter transportAdapter;
	private final BinaryMapPoiReaderAdapter poiAdapter;
	private final BinaryMapAddressReaderAdapter addressAdapter;
	private final BinaryMapRouteReaderAdapter routeAdapter;
	
	private static String BASEMAP_NAME = "basemap";

	
	public BinaryMapIndexReader(final RandomAccessFile raf) throws IOException {
		this.raf = raf;
		codedIS = CodedInputStream.newInstance(raf);
		codedIS.setSizeLimit(Integer.MAX_VALUE); // 2048 MB
		transportAdapter = new BinaryMapTransportReaderAdapter(this);
		addressAdapter = new BinaryMapAddressReaderAdapter(this);
		poiAdapter = new BinaryMapPoiReaderAdapter(this);
		routeAdapter = new BinaryMapRouteReaderAdapter(this);
		
		
		init();
	}
	
	/*private */BinaryMapIndexReader(final RandomAccessFile raf, boolean init) throws IOException {
		this.raf = raf;
		codedIS = CodedInputStream.newInstance(raf);
		codedIS.setSizeLimit(Integer.MAX_VALUE); // 2048 MB
		transportAdapter = new BinaryMapTransportReaderAdapter(this);
		addressAdapter = new BinaryMapAddressReaderAdapter(this);
		poiAdapter = new BinaryMapPoiReaderAdapter(this);
		routeAdapter = new BinaryMapRouteReaderAdapter(this);
		if(init) {
			
			init();
		}
	}
	
	public BinaryMapIndexReader(final RandomAccessFile raf, BinaryMapIndexReader referenceToSameFile) throws IOException {
		this.raf = raf;
		codedIS = CodedInputStream.newInstance(raf);
		codedIS.setSizeLimit(Integer.MAX_VALUE); // 2048 MB
		version = referenceToSameFile.version;
		dateCreated = referenceToSameFile.dateCreated;
		transportAdapter = new BinaryMapTransportReaderAdapter(this);
		addressAdapter = new BinaryMapAddressReaderAdapter(this);
		poiAdapter = new BinaryMapPoiReaderAdapter(this);
		routeAdapter = new BinaryMapRouteReaderAdapter(this);
		
		
		mapIndexes = new ArrayList<BinaryMapIndexReader.MapIndex>(referenceToSameFile.mapIndexes);
		
		
		poiIndexes = new ArrayList<PoiRegion>(referenceToSameFile.poiIndexes);
		addressIndexes = new ArrayList<AddressRegion>(referenceToSameFile.addressIndexes);
		transportIndexes = new ArrayList<TransportIndex>(referenceToSameFile.transportIndexes);
		routingIndexes = new ArrayList<RouteRegion>(referenceToSameFile.routingIndexes);
		indexes = new ArrayList<BinaryIndexPart>(referenceToSameFile.indexes);
		basemap = referenceToSameFile.basemap;
	}
	
	
	public long getDateCreated() {
		return dateCreated;
	}
	
	private void init() throws IOException {
		boolean initCorrectly = false;
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			
			String dir = Environment.getExternalStorageDirectory()+"/buffer2.txt";
			//writeFileSdcard(dir,"init()"+ " "+String.valueOf(tag)+"\n");
			
			switch (tag) {
			case 0:
				if(!initCorrectly){
					throw new IOException("Corrupted file. It should be ended as it starts with version"); //$NON-NLS-1$
				}
				return;
			case OsmandOdb.OsmAndStructure.VERSION_FIELD_NUMBER ://1
				version = codedIS.readUInt32();
				
//				writeFileSdcard(dir,"VERSION_FIELD"+ " "+version+"\n");
				
				break;
			case OsmandOdb.OsmAndStructure.DATECREATED_FIELD_NUMBER ://18
				dateCreated = codedIS.readInt64(); 
//				writeFileSdcard(dir,"DATECREATED_FIELD"+ " "+dateCreated+"\n");
				
				break;
			case OsmandOdb.OsmAndStructure.MAPINDEX_FIELD_NUMBER://6
				MapIndex mapIndex = new MapIndex();
				mapIndex.length = readInt();
				
//				writeFileSdcard(dir,"MAPINDEX_FIELD"+ " "+mapIndex.length+"\n");
				
				mapIndex.filePointer = codedIS.getTotalBytesRead();
				//writeFileSdcard(dir,"init//6  shezhi mapIndex.filePointer"+ " "+mapIndex.filePointer+"\n");
				
				int oldLimit = codedIS.pushLimit(mapIndex.length);
				readMapIndex(mapIndex, false);
				basemap = basemap || mapIndex.isBaseMap();
				codedIS.popLimit(oldLimit);
				codedIS.seek(mapIndex.filePointer + mapIndex.length);
				mapIndexes.add(mapIndex);
				indexes.add(mapIndex);
				break;
			case OsmandOdb.OsmAndStructure.ADDRESSINDEX_FIELD_NUMBER://7
				AddressRegion region = new AddressRegion();
				region.length = readInt();
				
//				writeFileSdcard(dir,"ADDRESSINDEX_FIELD"+ " "+region.length+"\n");
				region.filePointer = codedIS.getTotalBytesRead();
				if(addressAdapter != null){
					oldLimit = codedIS.pushLimit(region.length);
					addressAdapter.readAddressIndex(region);
					if(region.name != null){
						addressIndexes.add(region);
						indexes.add(region);
					}
					codedIS.popLimit(oldLimit);
				}
				codedIS.seek(region.filePointer + region.length);
				break;
			case OsmandOdb.OsmAndStructure.TRANSPORTINDEX_FIELD_NUMBER://4
				TransportIndex ind = new TransportIndex();
				ind.length = readInt();
				
//				writeFileSdcard(dir,"TRANSPORTINDEX_FIELD"+ " "+ind.length+"\n");
				ind.filePointer = codedIS.getTotalBytesRead();
				if (transportAdapter != null) {
					oldLimit = codedIS.pushLimit(ind.length);
					transportAdapter.readTransportIndex(ind);
					codedIS.popLimit(oldLimit);
					transportIndexes.add(ind);
					indexes.add(ind);
				}
				codedIS.seek(ind.filePointer + ind.length);
				break;
			case OsmandOdb.OsmAndStructure.ROUTINGINDEX_FIELD_NUMBER://9
				RouteRegion routeReg = new RouteRegion();
				routeReg.length = readInt();
				routeReg.filePointer = codedIS.getTotalBytesRead();
				if (routeAdapter != null) {
					oldLimit = codedIS.pushLimit(routeReg.length);
					routeAdapter.readRouteIndex(routeReg);
					codedIS.popLimit(oldLimit);
					routingIndexes.add(routeReg);
					indexes.add(routeReg);
				}
				codedIS.seek(routeReg.filePointer + routeReg.length);
				break;
			case OsmandOdb.OsmAndStructure.POIINDEX_FIELD_NUMBER://8
				PoiRegion poiInd = new PoiRegion();
				poiInd.length = readInt();
				poiInd.filePointer = codedIS.getTotalBytesRead();
				if (poiAdapter != null) {
					oldLimit = codedIS.pushLimit(poiInd.length);
					poiAdapter.readPoiIndex(poiInd, false);
					codedIS.popLimit(oldLimit);
					poiIndexes.add(poiInd);
					indexes.add(poiInd);
				}
				codedIS.seek(poiInd.filePointer + poiInd.length);
				break;
			case OsmandOdb.OsmAndStructure.VERSIONCONFIRM_FIELD_NUMBER ://32
				int cversion = codedIS.readUInt32();
				calculateCenterPointForRegions();
				initCorrectly = cversion == version;
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	private void calculateCenterPointForRegions(){
		for(AddressRegion reg : addressIndexes){
			for(MapIndex map : mapIndexes){
				if(Algorithms.objectEquals(reg.name, map.name)){
					if(map.getRoots().size() > 0){
						MapRoot mapRoot = map.getRoots().get(map.getRoots().size() - 1);
						double cy = (MapUtils.get31LatitudeY(mapRoot.getBottom()) + MapUtils.get31LatitudeY(mapRoot.getTop())) / 2;
						double cx = (MapUtils.get31LongitudeX(mapRoot.getLeft()) + MapUtils.get31LongitudeX(mapRoot.getRight())) / 2;
						reg.calculatedCenter = new LatLon(cy, cx);
						break;
					}
				}
			}
		}
	}
	
	public List<BinaryIndexPart> getIndexes() {
		return indexes;
	}
	
	public List<MapIndex> getMapIndexes() {
		return mapIndexes;
	}
	
	public List<RouteRegion> getRoutingIndexes() {
		return routingIndexes;
	}
	
	public boolean isBasemap() {
		return basemap;
	}
	
	public boolean containsMapData(){
		return mapIndexes.size() > 0;
	}
	
	public boolean containsPoiData(){
		return poiIndexes.size() > 0;
	}
	
	public boolean containsRouteData(){
		return routingIndexes.size() > 0;
	}
	
	public boolean containsPoiData(double latitude, double longitude) {
		for (PoiRegion index : poiIndexes) {
			if (index.rightLongitude >= longitude && index.leftLongitude <= longitude &&
					index.topLatitude >= latitude && index.bottomLatitude <= latitude) {
				return true;
			}
		}
		return false;
	}
	
	
	public boolean containsPoiData(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude) {
		for (PoiRegion index : poiIndexes) {
			if (index.rightLongitude >= leftLongitude && index.leftLongitude <= rightLongitude && 
					index.topLatitude >= bottomLatitude && index.bottomLatitude <= topLatitude) {
				return true;
			}
		}
		return false;
	}
	
	public boolean containsMapData(int tile31x, int tile31y, int zoom){
		for(MapIndex mapIndex :  mapIndexes){
			for(MapRoot root : mapIndex.getRoots()){
				if (root.minZoom <= zoom && root.maxZoom >= zoom) {
					if (tile31x >= root.left && tile31x <= root.right && root.top <= tile31y &&  root.bottom >= tile31y) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	public boolean containsMapData(int left31x, int top31y, int right31x, int bottom31y, int zoom){
		for(MapIndex mapIndex :  mapIndexes){
			for(MapRoot root : mapIndex.getRoots()){
				if (root.minZoom <= zoom && root.maxZoom >= zoom) {
					if (right31x >= root.left && left31x <= root.right && root.top <= bottom31y &&  root.bottom >= top31y) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	public boolean containsAddressData(){
		return addressIndexes.size() > 0;
	}
	
	public boolean hasTransportData(){
		return transportIndexes.size() > 0;
	}
	
	

	public RandomAccessFile getRaf() {
		return raf;
	}
	
	public int readByte() throws IOException{
		byte b = codedIS.readRawByte();
		if(b < 0){
			return b + 256;
		} else {
			return b;
		}
	}
	
	public final int readInt() throws IOException {
		int ch1 = readByte();
		int ch2 = readByte();
		int ch3 = readByte();
		int ch4 = readByte();
		return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + ch4);
	}
	
	
	public int getVersion() {
		return version;
	}
	
	

	protected void skipUnknownField(int tag) throws IOException {
		int wireType = WireFormat.getTagWireType(tag);
		if(wireType == WireFormat.WIRETYPE_FIXED32_LENGTH_DELIMITED){
			int length = readInt();
			codedIS.skipRawBytes(length);
		} else {
			codedIS.skipField(tag);
		}
	}
	
	
	/**
	 * Transport public methods
	 */
	public TIntObjectHashMap<TransportRoute> getTransportRoutes(int[] filePointers) throws IOException {
		TIntObjectHashMap<TransportRoute> result = new TIntObjectHashMap<TransportRoute>();
		Map<TransportIndex, TIntArrayList> groupPoints = new HashMap<TransportIndex, TIntArrayList>();
		for(int filePointer : filePointers){
			TransportIndex ind = getTransportIndex(filePointer);
			if (ind != null) {
				if (!groupPoints.containsKey(ind)) {
					groupPoints.put(ind, new TIntArrayList());
				}
				groupPoints.get(ind).add(filePointer);
			}
		}
		Iterator<Entry<TransportIndex, TIntArrayList> > it = groupPoints.entrySet().iterator();
		if(it.hasNext()){
			Entry<TransportIndex, TIntArrayList> e = it.next();
			TransportIndex ind = e.getKey();
			TIntArrayList pointers = e.getValue();
			pointers.sort();
			TIntObjectHashMap<String> stringTable = new TIntObjectHashMap<String>();
			for (int i = 0; i < pointers.size(); i++) {
				int filePointer = pointers.get(i);
				TransportRoute transportRoute = transportAdapter.getTransportRoute(filePointer, stringTable, false);
				result.put(filePointer, transportRoute);
			}
			transportAdapter.initializeStringTable(ind, stringTable);
			for(TransportRoute r : result.values(new TransportRoute[result.size()])){
				transportAdapter.initializeNames(false, r, stringTable);
			}
		}
		return result;
	}
	
	/**
	 * Transport public methods
	 */
	public List<net.osmand.data.TransportRoute> getTransportRouteDescriptions(TransportStop stop) throws IOException {
		TransportIndex ind = getTransportIndex(stop.getFileOffset());
		if(ind == null){
			return null;
		}
		List<net.osmand.data.TransportRoute> list = new ArrayList<TransportRoute>();
		TIntObjectHashMap<String> stringTable = new TIntObjectHashMap<String>();
		for(int filePointer : stop.getReferencesToRoutes()){
			TransportRoute tr = transportAdapter.getTransportRoute(filePointer, stringTable, true);
			if(tr != null){
				list.add(tr);				
			}
		}
		transportAdapter.initializeStringTable(ind, stringTable);
		for(TransportRoute route : list){
			transportAdapter.initializeNames(true, route, stringTable);
		}
		return list;
	}
	
	public boolean transportStopBelongsTo(TransportStop s){
		return getTransportIndex(s.getFileOffset()) != null;
	}
	
	public List<TransportIndex> getTransportIndexes() {
		return transportIndexes;
	}
	
	private TransportIndex getTransportIndex(int filePointer) {
		TransportIndex ind = null;
		for(TransportIndex i : transportIndexes){
			if(i.filePointer <= filePointer && (filePointer - i.filePointer) < i.length){
				ind = i;
				break;
			}
		}
		return ind;
	}
	
	public boolean containTransportData(double latitude, double longitude) {
		double x = MapUtils.getTileNumberX(TRANSPORT_STOP_ZOOM, longitude);
		double y = MapUtils.getTileNumberY(TRANSPORT_STOP_ZOOM, latitude);
		for (TransportIndex index : transportIndexes) {
			if (index.right >= x &&  index.left <= x && index.top <= y && index.bottom >= y) {
				return true;
			}
		}
		return false;
	}
	
	public boolean containTransportData(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude){
		double leftX = MapUtils.getTileNumberX(TRANSPORT_STOP_ZOOM, leftLongitude);
		double topY = MapUtils.getTileNumberY(TRANSPORT_STOP_ZOOM, topLatitude);
		double rightX = MapUtils.getTileNumberX(TRANSPORT_STOP_ZOOM, rightLongitude);
		double bottomY = MapUtils.getTileNumberY(TRANSPORT_STOP_ZOOM, bottomLatitude);
		for (TransportIndex index : transportIndexes) {
			if (index.right >= leftX &&  index.left <= rightX && index.top <= bottomY && index.bottom >= topY) {
				return true;
			}
		}
		return false;
	}
	
	public List<TransportStop> searchTransportIndex(SearchRequest<TransportStop> req) throws IOException {
		for (TransportIndex index : transportIndexes) {
			if (index.stopsFileLength == 0 || index.right < req.left || index.left > req.right || index.top > req.bottom
					|| index.bottom < req.top) {
				continue;
			}
			codedIS.seek(index.stopsFileOffset);
			int oldLimit = codedIS.pushLimit(index.stopsFileLength);
			int offset = req.searchResults.size();
			transportAdapter.searchTransportTreeBounds(0, 0, 0, 0, req);
			codedIS.popLimit(oldLimit);
			if (req.stringTable != null) {
				transportAdapter.initializeStringTable(index, req.stringTable);
				for (int i = offset; i < req.searchResults.size(); i++) {
					TransportStop st = req.searchResults.get(i);
					transportAdapter.initializeNames(req.stringTable, st);
				}
			}
		}
		if(req.numberOfVisitedObjects > 0) {
			log.debug("Search is done. Visit " + req.numberOfVisitedObjects + " objects. Read " + req.numberOfAcceptedObjects + " objects."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			log.debug("Read " + req.numberOfReadSubtrees + " subtrees. Go through " + req.numberOfAcceptedSubtrees + " subtrees.");   //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
		}
		return req.getSearchResults();
	}
	
	/**
	 * Address public methods
	 */
	public List<String> getRegionNames(){
		List<String> names = new ArrayList<String>();
		for(AddressRegion r : addressIndexes){
			names.add(r.name);
		}
		return names;
	}
	
	public LatLon getRegionCenter(String name) {
		AddressRegion rg = getRegionByName(name);
		if (rg != null) {
			return rg.calculatedCenter;
		}
		return null;
	}
	
	private AddressRegion getRegionByName(String name){
		for(AddressRegion r : addressIndexes){
			if(r.name.equals(name)){
				return r;
			}
		}
		throw new IllegalArgumentException(name);
	}
	
	public List<City> getCities(String region, SearchRequest<City> resultMatcher,  
			int cityType) throws IOException {
		return getCities(region, resultMatcher, null, false, cityType);
	}
	public List<City> getCities(String region, SearchRequest<City> resultMatcher, StringMatcher matcher, boolean useEn, 
			int cityType) throws IOException {
		List<City> cities = new ArrayList<City>();
		AddressRegion r = getRegionByName(region);
		for(CitiesBlock block : r.cities) {
			if(block.type == cityType) {
				codedIS.seek(block.filePointer);
				int old = codedIS.pushLimit(block.length);
				addressAdapter.readCities(cities, resultMatcher, matcher, useEn);
				codedIS.popLimit(old);
			}
		}
		return cities;
	}
	
	public List<City> getCities(AddressRegion region, SearchRequest<City> resultMatcher,  
			int cityType) throws IOException {
		return getCities(region, resultMatcher, null, false, cityType);
	}
	public List<City> getCities(AddressRegion region, SearchRequest<City> resultMatcher, StringMatcher matcher, boolean useEn, 
			int cityType) throws IOException {
		List<City> cities = new ArrayList<City>();
		for(CitiesBlock block : region.cities) {
			if(block.type == cityType) {
				codedIS.seek(block.filePointer);
				int old = codedIS.pushLimit(block.length);
				addressAdapter.readCities(cities, resultMatcher, matcher, useEn);
				codedIS.popLimit(old);
			}
		}
		return cities;
	}
	
	public int preloadStreets(City c, SearchRequest<Street> resultMatcher) throws IOException {
		checkAddressIndex(c.getFileOffset());
		codedIS.seek(c.getFileOffset());
		int size = codedIS.readRawVarint32();
		int old = codedIS.pushLimit(size);
		addressAdapter.readCityStreets(resultMatcher, c);
		codedIS.popLimit(old);
		return size;
	}
	
	private void checkAddressIndex(int offset){
		boolean ok = false;
		for(AddressRegion r : addressIndexes){
			if(offset >= r.filePointer  && offset <= (r.length + r.filePointer)){
				ok = true;
				break;
			}
		}
		if(!ok){
			throw new IllegalArgumentException("Illegal offset " + offset); //$NON-NLS-1$
		}
	}
	
	public void preloadBuildings(Street s, SearchRequest<Building> resultMatcher) throws IOException {
		checkAddressIndex(s.getFileOffset());
		codedIS.seek(s.getFileOffset());
		int size = codedIS.readRawVarint32();
		int old = codedIS.pushLimit(size);
		City city = s.getCity();
		addressAdapter.readStreet(s, resultMatcher, true, 0, 0, city != null  && city.isPostcode() ? city.getName() : null);
		codedIS.popLimit(old);
	}
	
	
	/**
	 * Map public methods 
	 */

	private void readMapIndex(MapIndex index, boolean onlyInitEncodingRules) throws IOException {
		int defaultId = 1;
		int oldLimit ;
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			
			String dir = Environment.getExternalStorageDirectory()+"/buffer2.txt";
			//writeFileSdcard(dir,"readMapIndex"+ " "+String.valueOf(tag)+"\n");
			
			switch (tag) {
			case 0:
				// encoding rules are required!
				if (onlyInitEncodingRules) {
					index.finishInitializingTags();
				}
				return;
			case OsmandOdb.OsmAndMapIndex.NAME_FIELD_NUMBER ://2
				index.setName(codedIS.readString());
//				writeFileSdcard(dir,"NAME_FIELD"+" "+index.name+"\n");
				
				break;
			case OsmandOdb.OsmAndMapIndex.RULES_FIELD_NUMBER ://4
				if (onlyInitEncodingRules) {
					int len = codedIS.readInt32();
					
//					writeFileSdcard(dir,"RULES_FIELD"+" "+len+"\n");
					
					oldLimit = codedIS.pushLimit(len);
					readMapEncodingRule(index, defaultId++);
					codedIS.popLimit(oldLimit);
				} else {
					skipUnknownField(t);
				}
				break;
			case OsmandOdb.OsmAndMapIndex.LEVELS_FIELD_NUMBER ://5
				int length = readInt();
				
//				writeFileSdcard(dir,"LEVELS_FIELD"+" "+length+"\n");
				
				int filePointer = codedIS.getTotalBytesRead();
				if (!onlyInitEncodingRules) {
					oldLimit = codedIS.pushLimit(length);
					MapRoot mapRoot = readMapLevel(new MapRoot());
					mapRoot.length = length;
					
					//writeFileSdcard(dir,"readMapIndex//5  shezhi mapRoot.filePointer"+ " "+filePointer+"\n");
					mapRoot.filePointer = filePointer;
					index.getRoots().add(mapRoot);
					codedIS.popLimit(oldLimit);
				}
				
				//writeFileSdcard(dir,"readMapIndex//5  codedIS.seek(filePointer + length)"+ " "+(filePointer + length)+"\n");
				
				codedIS.seek(filePointer + length);
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	
	private void readMapEncodingRule(MapIndex index, int id) throws IOException {
		int type = 0;
		String tags = null;
		String val = null;
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			
			String dir = Environment.getExternalStorageDirectory()+"/buffer2.txt";
			//writeFileSdcard(dir,"readMapEncodingRule"+" "+String.valueOf(tag)+"\n");
			
			
			switch (tag) {
			case 0:
				index.initMapEncodingRule(type, id, tags, val);
				return;
			case MapEncodingRule.VALUE_FIELD_NUMBER ://5
				val = codedIS.readString().intern();
				
//				writeFileSdcard(dir,"VALUE_FIELD"+" "+val+"\n");
				break;
			case MapEncodingRule.TAG_FIELD_NUMBER ://3
				tags = codedIS.readString().intern();
				
//				writeFileSdcard(dir,"TAG_FIELD"+" "+tags+"\n");
				break;
			case MapEncodingRule.TYPE_FIELD_NUMBER ://10
				type = codedIS.readUInt32();
				
//				writeFileSdcard(dir,"TYPE_FIELD"+" "+type+"\n");
				break;
			case MapEncodingRule.ID_FIELD_NUMBER ://7
				id = codedIS.readUInt32();
				
//				writeFileSdcard(dir,"ID_FIELD"+" "+id+"\n");
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}


	private MapRoot readMapLevel(MapRoot root) throws IOException {
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			
			String dir = Environment.getExternalStorageDirectory()+"/buffer2.txt";
			//writeFileSdcard(dir,"readMapLevel"+" "+String.valueOf(tag)+"\n");
			
			switch (tag) {
			case 0:
				return root;
			case MapRootLevel.BOTTOM_FIELD_NUMBER ://6
				root.bottom = codedIS.readInt32();
				
				//writeFileSdcard(dir,"BOTTOM_FIELD"+" "+root.bottom+"\n");
				break;
			case MapRootLevel.LEFT_FIELD_NUMBER ://3
				root.left = codedIS.readInt32();
				
				//writeFileSdcard(dir,"LEFT_FIELD"+" "+root.left+"\n");
				break;
			case MapRootLevel.RIGHT_FIELD_NUMBER ://4
				root.right = codedIS.readInt32();
				
				//writeFileSdcard(dir,"RIGHT_FIELD"+" "+root.right+"\n");
				break;
			case MapRootLevel.TOP_FIELD_NUMBER ://5
				root.top = codedIS.readInt32();
				
				//writeFileSdcard(dir,"TOP_FIELD"+" "+root.top+"\n");
				break;
			case MapRootLevel.MAXZOOM_FIELD_NUMBER ://1
				root.maxZoom = codedIS.readInt32();
				
				//writeFileSdcard(dir,"MAXZOOM_FIELD"+" "+root.maxZoom+"\n");
				break;
			case MapRootLevel.MINZOOM_FIELD_NUMBER ://2
				root.minZoom = codedIS.readInt32();
				
				//writeFileSdcard(dir,"MINZOOM_FIELD"+" "+root.minZoom+"\n");
				break;
			case MapRootLevel.BOXES_FIELD_NUMBER ://7
				int length = readInt();
				
				//writeFileSdcard(dir,"BOXES_FIELD"+" "+length+"\n");
				
				int filePointer = codedIS.getTotalBytesRead();
				if (root.trees != null) {
					MapTree r = new MapTree();
					// left, ... already initialized
					r.length = length;
					r.filePointer = filePointer;
					
					//writeFileSdcard(dir,"readMapLevel//7  shezhi tree.filePointer"+ " "+filePointer+"\n");
					
					int oldLimit = codedIS.pushLimit(r.length);
					readMapTreeBounds(r, root.left, root.right, root.top, root.bottom);
					root.trees.add(r);
					codedIS.popLimit(oldLimit);
				}
				
				
				codedIS.seek(filePointer + length);
				break;
			case MapRootLevel.BLOCKS_FIELD_NUMBER ://15
				codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
				
				//?
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
		
	}
	
	private void readMapTreeBounds(MapTree tree, int aleft, int aright, int atop, int abottom) throws IOException {
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			
			String dir = Environment.getExternalStorageDirectory()+"/buffer2.txt";
			//writeFileSdcard(dir,"readMapTreeBounds"+" "+String.valueOf(tag)+"\n");
			
			switch (tag) {
			case 0:
				return;
			case MapDataBox.BOTTOM_FIELD_NUMBER ://4
				
				int temp_BOTTOM = codedIS.readSInt32();
				tree.bottom = temp_BOTTOM + abottom;
				
//				writeFileSdcard(dir,"BOTTOM_FIELD"+" "+tree.bottom+"\n");
				break;
			case MapDataBox.LEFT_FIELD_NUMBER ://1
				
				int temp_LEFT = codedIS.readSInt32();
				tree.left = temp_LEFT + aleft;
				
				//writeFileSdcard(dir,"LEFT_FIELD"+" "+tree.left+"\n");
				break;
			case MapDataBox.RIGHT_FIELD_NUMBER ://2
				
				int temp_RIGHT = codedIS.readSInt32();
				
				tree.right = temp_RIGHT + aright;
				
				//writeFileSdcard(dir,"RIGHT_FIELD"+" "+tree.right+"\n");
				break;
			case MapDataBox.TOP_FIELD_NUMBER ://3
				
				int temp_TOP = codedIS.readSInt32();
				
				tree.top = temp_TOP + atop;
				
				//writeFileSdcard(dir,"TOP_FIELD"+" "+tree.top+"\n");
				break;
			case MapDataBox.OCEAN_FIELD_NUMBER ://6
				if(codedIS.readBool()) {
					tree.ocean = Boolean.TRUE;
					//writeFileSdcard(dir,"OCEAN_FIELD"+" "+tree.ocean+"\n");
				} else {
					tree.ocean = Boolean.FALSE;
					//writeFileSdcard(dir,"OCEAN_FIELD"+" "+tree.ocean+"\n");
				}
				break;
			case MapDataBox.SHIFTTOMAPDATA_FIELD_NUMBER ://5
				
				int temp_int = readInt();
				
				tree.mapDataBlock = temp_int + tree.filePointer;
				
				//writeFileSdcard(dir,"readMapTreeBounds//5  shezhi tree.mapDataBlock"+ " "+tree.mapDataBlock+"\n");
				
				//writeFileSdcard(dir,"SHIFTTOMAPDATA_FIELD"+" "+tree.mapDataBlock+"\n");
				break;
		
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	public void createFile(String path, byte[] content) throws IOException {  
		  
        FileOutputStream fos = new FileOutputStream(path,true);  
  
        fos.write(content);  
        fos.close();  
    }  
	
	
	/**
	 * �㷨�е�bottom��top�Ƿ������ģ�bottom��top��
	 * @param req
	 * @return
	 * @throws IOException
	 */
	public List<BinaryMapDataObject> searchMapIndex(SearchRequest<BinaryMapDataObject> req) throws IOException {
		req.numberOfVisitedObjects = 0;
		req.numberOfAcceptedObjects = 0;
		req.numberOfAcceptedSubtrees = 0;
		req.numberOfReadSubtrees = 0;
		List<MapTree> foundSubtrees = new ArrayList<MapTree>();
		
		String dir = Environment.getExternalStorageDirectory()+"/";
		File f =new File(dir,"buffer.txt");
		
		//FileOutputStream out = new FileOutputStream(f);
		
		String bufferdir = dir+"buffer.txt";
		String bufferdir2 = dir+"buffer2.txt";
		String layer_dir = dir+"Treelayertest.txt";
		
		File layerfile = new File(dir,"Treelayertest.txt");
		if(!layerfile.exists()){
			layerfile.createNewFile();
		}
		
		int i =1;
		log.warn("mapIndexes.size"+" "+mapIndexes.size());
		
		for (MapIndex mapIndex : mapIndexes) {
			
			//
		
			if(!f.exists()){
				f.createNewFile();
			}
			//System.getProperty("line.separator"); //\n
			String objectnum = "di"+(i)+"loop"+"\n";
			//writeFileSdcard(bufferdir,"test");
//			for(int c=0;c<codedIS.buffer.length;c++){
//				createFile(bufferdir,codedIS.buffer[c]);
//			}
			// lazy initializing rules
			if(mapIndex.encodingRules.isEmpty()) {
				
				
				codedIS.seek(mapIndex.filePointer);
				int oldLimit = codedIS.pushLimit(mapIndex.length);
				readMapIndex(mapIndex, true);
				codedIS.popLimit(oldLimit);
			}
			int loop1 =1;
			int loop2 =1;
			for (MapRoot index : mapIndex.getRoots()) {
				//
				android.util.Log.i("test",String.format("minZoom:%s , maxZoom:%s", String.valueOf(index.minZoom),String.valueOf(index.maxZoom)));
				//�������ŵȼ���ȷ����Ҫ�Ķ���
//				if (index.minZoom <= req.zoom && index.maxZoom >= req.zoom) 
				if (true) 
				{
					/**
					 * (ԭ���㷨)��ͼ���кܶ����������ж�ÿ�����ĸ��ڵ��Ƿ����㷶Χ�����󡣣��ж�����ķ�Χ�Ƿ���ֿ�ķ�Χ�н�����
					 */
					/*if (index.right < req.left || index.left > req.right || index.top > req.bottom || index.bottom < req.top) {
						continue;
					}*/
					
//					android.util.Log.i("test",String.format("index.right:%d,index.left:%d,index.top:%d,index.bottom:%d,req.left:%d,req.top%d",
//							index.right,index.left,index.top,index.bottom,req.left,req.top));
					
					/**
					 * (�Լ�������㷨)�ж�����Ķ��Ƿ�����������
					 */
					if(!(index.right > req.left && index.left < req.left && index.top <req.bottom && index.bottom > req.bottom))
					{
						continue;
					}
					
					android.util.Log.i("test","�ҵ�����Ҫ����");
					
					mapIndex.getRoots().size();
					
					
					// lazy initializing trees
					if(index.trees == null){
						index.trees = new ArrayList<MapTree>();
						
						codedIS.seek(index.filePointer);
						int oldLimit = codedIS.pushLimit(index.length);
						readMapLevel(index);
						codedIS.popLimit(oldLimit);
					}
					
					//������ͬ��
					for (MapTree tree : index.trees) {
						//(ԭ���㷨���ж�)
						/*if (tree.right < req.left || tree.left > req.right || tree.top > req.bottom || tree.bottom < req.top) {
							continue;
						}*/
						
						//������жϣ�˼·ͬ��
						if(!(tree.right > req.left && tree.left < req.left && tree.top < req.bottom && tree.bottom > req.bottom)){
							continue;
						}
						
						codedIS.seek(tree.filePointer);
						int oldLimit = codedIS.pushLimit(tree.length);
						searchMapTreeBounds(tree, index, req, foundSubtrees);
						codedIS.popLimit(oldLimit);
					}
					
					Collections.sort(foundSubtrees, new Comparator<MapTree>() {
						@Override
						public int compare(MapTree o1, MapTree o2) {
							return o1.mapDataBlock < o2.mapDataBlock ? -1 : (o1.mapDataBlock == o2.mapDataBlock ? 0 : 1);
						}
					});
					int temp = 1;
					for(MapTree tree : foundSubtrees) {
						if(!req.isCancelled()){
							
							codedIS.seek(tree.mapDataBlock);
							int length = codedIS.readRawVarint32();
							
							
							//writeFileSdcard(bufferdir,"length"+ " "+length+"\n");
							int oldLimit = codedIS.pushLimit(length);
							readMapDataBlocks(req, tree, mapIndex);
							codedIS.popLimit(oldLimit);
						}
						
					}
					
					foundSubtrees.clear();
				}
				
			}
			//write over
			i++;
		}
		if(req.numberOfVisitedObjects > 0) {
			log.info("Search is done. Visit " + req.numberOfVisitedObjects + " objects. Read " + req.numberOfAcceptedObjects + " objects."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			log.info("Read " + req.numberOfReadSubtrees + " subtrees. Go through " + req.numberOfAcceptedSubtrees + " subtrees.");   //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
		}
		return req.getSearchResults();
	}
	
	public List<BinaryMapDataObject> searchMapIndex(SearchRequest<BinaryMapDataObject> req, MapIndex mapIndex) throws IOException {
		req.numberOfVisitedObjects = 0;
		req.numberOfAcceptedObjects = 0;
		req.numberOfAcceptedSubtrees = 0;
		req.numberOfReadSubtrees = 0;
		List<MapTree> foundSubtrees = new ArrayList<MapTree>();
		
		// lazy initializing rules
		if(mapIndex.encodingRules.isEmpty()) {
			codedIS.seek(mapIndex.filePointer);
			int oldLimit = codedIS.pushLimit(mapIndex.length);
			readMapIndex(mapIndex, true);
			codedIS.popLimit(oldLimit);
		}
		
		for (MapRoot level : mapIndex.getRoots()) {
			if ((level.minZoom <= req.zoom && level.maxZoom >= req.zoom) || req.zoom == -1) {
				if (level.right < req.left || level.left > req.right || level.top > req.bottom || level.bottom < req.top) {
					continue;
				}
				
				// lazy initializing trees
				if(level.trees == null){
					level.trees = new ArrayList<MapTree>();
					codedIS.seek(level.filePointer);
					int oldLimit = codedIS.pushLimit(level.length);
					readMapLevel(level);
					codedIS.popLimit(oldLimit);
				}
				
				for (MapTree tree : level.trees) {
					if (tree.right < req.left || tree.left > req.right || tree.top > req.bottom || tree.bottom < req.top) {
						continue;
					}
					codedIS.seek(tree.filePointer);
					int oldLimit = codedIS.pushLimit(tree.length);
					searchMapTreeBounds(tree, level, req, foundSubtrees);
					codedIS.popLimit(oldLimit);
				}
				
				Collections.sort(foundSubtrees, new Comparator<MapTree>() {
					@Override
					public int compare(MapTree o1, MapTree o2) {
						return o1.mapDataBlock < o2.mapDataBlock ? -1 : (o1.mapDataBlock == o2.mapDataBlock ? 0 : 1);
					}
				});
				for(MapTree tree : foundSubtrees) {
					if(!req.isCancelled()){
						codedIS.seek(tree.mapDataBlock);
						int length = codedIS.readRawVarint32();
						int oldLimit = codedIS.pushLimit(length);
						readMapDataBlocks(req, tree, mapIndex);
						codedIS.popLimit(oldLimit);
					}
				}
				foundSubtrees.clear();
			}
			
		}
	
		
		if(req.numberOfVisitedObjects > 0) {
			log.info("Search is done. Visit " + req.numberOfVisitedObjects + " objects. Read " + req.numberOfAcceptedObjects + " objects."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			log.info("Read " + req.numberOfReadSubtrees + " subtrees. Go through " + req.numberOfAcceptedSubtrees + " subtrees.");   //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
		}
		return req.getSearchResults();
	}
	
	protected void readMapDataBlocks(SearchRequest<BinaryMapDataObject> req, MapTree tree, MapIndex root) throws IOException {
		List<BinaryMapDataObject> tempResults = null;
		long baseId  = 0;
		while (true) {
			if (req.isCancelled()) {
				return;
			}
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			
			final String dir = Environment.getExternalStorageDirectory()+"/test.txt";
			String layer_dir = Environment.getExternalStorageDirectory()+"/Treelayertest.txt";
			//writeFileSdcard(layer_dir,"readMapDataBlocks"+" "+String.valueOf(tag)+"\n");
			File buffer = new File(dir);
			if(!buffer.exists()){
				buffer.createNewFile();
			}
			
			
			switch (tag) {
			case 0:
				if(tempResults != null) {
					for(BinaryMapDataObject obj : tempResults) {
						req.publish(obj);
					}
				}
				return;
			case MapDataBlock.BASEID_FIELD_NUMBER://10
				baseId = codedIS.readUInt64();
				
				//writeFileSdcard(layer_dir,"BASEID_FIELD"+" "+baseId+"\n");
				break;
			case MapDataBlock.DATAOBJECTS_FIELD_NUMBER://12����ȡ����Ҫ��Ķ���
				int length = codedIS.readRawVarint32();
				
				//writeFileSdcard(layer_dir,"DATAOBJECTS_FIELD"+" "+length+"\n");
				
				int oldLimit = codedIS.pushLimit(length);
				final BinaryMapDataObject mapObject = readMapDataObject(tree, req, root);
				if (mapObject != null) {
					mapObject.setId(mapObject.getId() + baseId);
					if (tempResults == null) {
						tempResults = new ArrayList<BinaryMapDataObject>();
					}
					
					//writeFileSdcard(dir,"object_type "+" "+mapObject.types.toString()+"\n");
					//writeFileSdcard(dir,"object_name "+" "+mapObject.objectNames.toString()+"\n");
				//	mapObject.getObjectNames().forEachEntry(mapObject.objectNames);
					
//					android.util.Log.i("test","-----</-----");
					String temp="id:"+String.valueOf(mapObject.getId()) + " , ";
					boolean isvalue=false;
					for (int j = 0; j < mapObject.getTypes().length; j++) {
						int wholeType = mapObject.getTypes()[j];
//						log.warn("test:mytypes: Types()"+(j+1)+" "+wholeType);
						int layer = 0;
						if (mapObject.getPointsLength() > 1) {
							layer = mapObject.getSimpleLayer();
						}

						TagValuePair pair = mapObject.getMapIndex().decodeType(wholeType);
						String tag_name = pair.tag;
						String value_name = pair.value;
//						android.util.Log.i("test","tag_name:"+tag_name+"  ,  value_name:"+ value_name);
						temp+="{tag_name:"+tag_name+"  ,  value_name:"+ value_name+"}\t";
//						writeFileSdcard(dir,"tag_name "+" "+tag_name+"\n");	
						
						//�ڴ˴��ж�tag_nameΪadminlevel����������������������������Ӧ�ĵ㣬��֤����Щ������
						if(pair.tag.equals("admin_level"))
						{
							isvalue=true;
						}
					}
//					android.util.Log.i("test","----/>----");
//					if(isvalue)
						SaveObjectInfo.getInstance().writeText(temp);
					
					//mapObject.getMapIndex().decodeType(mapObject.types[0]);
//					String temp = mapObject.getMapIndex().decodeType(mapObject.types[0]).tag;
					
					//
					final TIntObjectHashMap<String> map = mapObject.getObjectNames();
					if (map != null) {
						map.forEachEntry(new TIntObjectProcedure<String>() {
							@Override
							public boolean execute(int tag, String name) {
								if (name != null && name.trim().length() > 0) {
									boolean isName = tag == mapObject.getMapIndex().nameEncodingType;
									String nameTag = isName ? "" : mapObject.getMapIndex().decodeType(tag).tag;
									boolean skip = false;
									
									//writeFileSdcard(dir,"object_name "+" "+name+"\n");	
									//writeFileSdcard(dir,"object_nameTag "+" "+nameTag+"\n");	
									// not completely correct we should check "name"+rc.preferredLocale
//									if (isName && !rc.preferredLocale.equals("") && 
//											map.containsKey(obj.getMapIndex().nameEnEncodingType)) {
//										skip = true;
//									} 
//									if (tag == obj.getMapIndex().nameEnEncodingType && !rc.useEnglishNames) {
//										skip = true;
//									}
//									if(!skip) {
//										createTextDrawInfo(obj, render, rc, pair, xMid, yMid, path, points, name, nameTag);
//									}
								}
								return true;
							}
						});
						
						
					}
					
					//(ԭ�к���)������Ҫ��Ķ���ӵ�������
//					tempResults.add(mapObject);
					log.debug("objectType  : "+mapObject.objectType);
				}
				codedIS.popLimit(oldLimit);
				break;
			case MapDataBlock.STRINGTABLE_FIELD_NUMBER://15
				length = codedIS.readRawVarint32();
				
				//writeFileSdcard(layer_dir,"STRINGTABLE_FIELD"+" "+length+"\n");
				
				oldLimit = codedIS.pushLimit(length);
				if (tempResults != null) {
					List<String> stringTable = readStringTable();
					for (int i = 0; i < tempResults.size(); i++) {
						BinaryMapDataObject rs = tempResults.get(i);
						if (rs.objectNames != null) {
							int[] keys = rs.objectNames.keys();
							for (int j = 0; j < keys.length; j++) {
								rs.objectNames.put(keys[j], stringTable.get(rs.objectNames.get(keys[j]).charAt(0)));
							}
						}
					}
				} else {
					codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
				}
				codedIS.popLimit(oldLimit);
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}

	}
	
	public void writeFileSdcard(String fileName,String message){ 

	       try{ 

	        //FileOutputStream fout = openFileOutput(fileName, MODE_PRIVATE);

	       FileOutputStream fout = new FileOutputStream(fileName,true);
	       

	        byte [] bytes = message.getBytes(); 
	        

	        fout.write(bytes);
	        
	        

	         fout.close(); 

	        } 

	       catch(Exception e){

	        e.printStackTrace(); 

	       } 

	   }
	
	protected void searchMapTreeBounds(MapTree current, MapTree parent,
			SearchRequest<BinaryMapDataObject> req, List<MapTree> foundSubtrees) throws IOException {
		int init = 0;
		req.numberOfReadSubtrees++;
		while(true){
			if(req.isCancelled()){
				return;
			}
			
			String ss = Environment.getExternalStorageDirectory()+"/";
			String layer_dir = ss+"Treelayertest.txt";
			File file = new File(ss,"Treelayertest.txt");
			if(!file.exists()){
				try {
					file.createNewFile();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			
			//String dir = Environment.getExternalStorageDirectory()+"/buffer2.txt";
			
			
			
			
//			int layer = 1;
//			writeFileSdcard(layer_dir,"layers"+" "+(layer++)+"\n");
			
			if(init == 0xf){
				init = 0;
				// coordinates are init
				//(ԭ���㷨)�Ƚ�ÿ����������������Ƿ��н���
				/*if(current.right < req.left || current.left > req.right || current.top > req.bottom || current.bottom < req.top){
					
					return;
				} else {
					req.numberOfAcceptedSubtrees++;
				}*/
				
				if(!(current.right > req.left && current.left < req.left && current.top < req.bottom && current.bottom > req.bottom)){
					
					return;
				} else {
					req.numberOfAcceptedSubtrees++;
				}
			}
			//writeFileSdcard(layer_dir,"searchMapTreeBounds"+" "+String.valueOf(tag)+"\n");
			
			switch (tag) {
			case 0:
				
				//writeFileSdcard(layer_dir,"move to next node:2"+" "+"\n");
				
				return;
			case MapDataBox.BOTTOM_FIELD_NUMBER ://4
				int bottom_temp = codedIS.readSInt32();
				current.bottom = bottom_temp + parent.bottom;
				init |= 1;
				
				//writeFileSdcard(dir,"BOTTOM_FIELD"+" "+current.bottom+"\n");
				
				break;
			case MapDataBox.LEFT_FIELD_NUMBER ://1
				
				int left_temp = codedIS.readSInt32();
				
				current.left = left_temp + parent.left;
				init |= 2;
				
				//writeFileSdcard(dir,"LEFT_FIELD"+" "+current.left+"\n");
				break;
			case MapDataBox.RIGHT_FIELD_NUMBER ://2
				
				int right_temp = codedIS.readSInt32();
				
				current.right = right_temp + parent.right;
				init |= 4;
				
				//writeFileSdcard(dir,"RIGHT_FIELD"+" "+current.right+"\n");
				break;
			case MapDataBox.TOP_FIELD_NUMBER ://3
				
				int top_temp = codedIS.readSInt32();
				
				current.top = top_temp + parent.top;
				init |= 8;
				
				//writeFileSdcard(dir,"TOP_FIELD"+" "+current.top+"\n");
				break;
			case MapDataBox.SHIFTTOMAPDATA_FIELD_NUMBER ://5
				req.numberOfAcceptedSubtrees ++;
				current.mapDataBlock = readInt() + current.filePointer;
				
				//writeFileSdcard(layer_dir,"current.mapDataBlock: "+current.mapDataBlock+"\n");
				
				foundSubtrees.add(current);
				
				//writeFileSdcard(dir,"SHIFTTOMAPDATA_FIELD"+" "+current.mapDataBlock+"\n");
				
				break;
			case MapDataBox.OCEAN_FIELD_NUMBER ://6
				if(codedIS.readBool()) {
					current.ocean = Boolean.TRUE;
					//writeFileSdcard(dir,"OCEAN_FIELD"+" "+current.ocean+"\n");
				} else {
					current.ocean = Boolean.FALSE;
					//writeFileSdcard(dir,"OCEAN_FIELD"+" "+current.ocean+"\n");
				}
				req.publishOceanTile(current.ocean);
				break;
			case MapDataBox.BOXES_FIELD_NUMBER ://7
				// left, ... already initialized
				MapTree child = new MapTree();
				child.length = readInt();
				
				//
				//writeFileSdcard(dir,"child.length"+" "+child.length+"\n");
				
				child.filePointer = codedIS.getTotalBytesRead();
				int oldLimit = codedIS.pushLimit(child.length);
				if(current.ocean != null ){
					child.ocean = current.ocean;
				}
				searchMapTreeBounds(child, current, req, foundSubtrees);
				codedIS.popLimit(oldLimit);
				
				//writeFileSdcard(layer_dir,"searchMapTreeBounds//7"+"means uplevel "+"\n");
				
				codedIS.seek(child.filePointer + child.length);
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	private int MASK_TO_READ = ~((1 << SHIFT_COORDINATES) - 1);
	private BinaryMapDataObject readMapDataObject(MapTree tree , SearchRequest<BinaryMapDataObject> req, 
			MapIndex root) throws IOException {
		
		int tag = WireFormat.getTagFieldNumber(codedIS.readTag());
		
		String dir = Environment.getExternalStorageDirectory()+"/buffer2.txt";
		//writeFileSdcard(dir,"readMapDataObject"+" "+String.valueOf(tag)+"\n");
		
		
		boolean area = OsmandOdb.MapData.AREACOORDINATES_FIELD_NUMBER == tag;
		if(!area && OsmandOdb.MapData.COORDINATES_FIELD_NUMBER != tag) {
			throw new IllegalArgumentException();
		}
		req.cacheCoordinates.clear();
		int size = codedIS.readRawVarint32();
		
		//
		//writeFileSdcard(dir,"size"+" "+size+"\n");
		
		int old = codedIS.pushLimit(size);
		int px = tree.left & MASK_TO_READ;
		int py = tree.top & MASK_TO_READ;
		boolean contains = false;
		int minX = Integer.MAX_VALUE;
		int maxX = 0;
		int minY = Integer.MAX_VALUE;
		int maxY = 0;
		req.numberOfVisitedObjects++;
		
		//�Լ���ӵ��㷨
		List<Double> polygonX=new ArrayList<Double>();
		List<Double> polygonY=new ArrayList<Double>();
		
		while(codedIS.getBytesUntilLimit() > 0){
			
			int temp1 = codedIS.readSInt32();
			int temp2 = codedIS.readSInt32();
			//writeFileSdcard(dir,"temp1"+" "+temp1+"\n");
			//writeFileSdcard(dir,"temp2"+" "+temp2+"\n");
			
			int x = (temp1 << SHIFT_COORDINATES) + px;
			int y = (temp2 << SHIFT_COORDINATES) + py;
			
			//int x = (codedIS.readSInt32() << SHIFT_COORDINATES) + px;
			//int y = (codedIS.readSInt32() << SHIFT_COORDINATES) + py;
			req.cacheCoordinates.add(x);
			req.cacheCoordinates.add(y);
			
			//�����Լ�����Ľڵ�߽�
			polygonX.add((double)x);
			polygonY.add((double)y);
			
			px = x;
			py = y;
			//(ԭ���ж�)�ж��Ƿ���һ����������Ķ��������棬����ǣ���ö�������������Ҫ�Ķ���ԭ���жϣ�
			/*if(!contains && req.left <= x && req.right >= x && req.top <= y && req.bottom >= y){
				contains = true;
			}
			if(!contains){
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
			}*/
			
		}
		
		//(�Լ����ж�)(����)�жϣ�������ڶ������С���������ڣ���Ҳ�����Ҫ��
		/*if(req.left > minX && req.left <maxX && req.bottom <maxY && req.bottom >minY)
		{
			contains=true;
		}*/
		
		//(�Լ����ж�)�жϵ��Ƿ��ڶ�����
		try {
			contains=PointInPolygon.pointInPolygon((double)req.left, (double)req.bottom, polygonX, polygonY);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		

		//(�Լ����ж�)������ĵ㣨��Ȼ��Ȼʹ�÷�Χ�����������þ�����ͬ������ά����ͬ��
		/*if(req.left!=req.right || req.bottom!=req.top)
		{
			contains=false;
			android.util.Log.e("BinaryMapIndexReader.readMapDataObject()","���жϵ��Ƿ����ڸö���ʱ���֣�����ĵ㲻�Ϸ������ε����Ҿ��Ȳ��ȣ�����ά�Ȳ���");
			
		}*/
		
		/*
		//(ԭ���жϣ�ȡ�������С���������뱾����Χ����)
		if(!contains){
			//û�д��󣬳�����bottom��topҪ���������Ƿ������ġ�����Ŀ���ǣ������������������С����������ķ�Χ�н�������ô�ö���Ҳ����Ҫ��
			if(maxX >= req.left && minX <= req.right && minY <= req.bottom && maxY >= req.top){
				contains = true;
			}
			
		}
		*/
		
		codedIS.popLimit(old);
		if(!contains){
			codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
			return null;
		}
		
		// read 
		
		List<TIntArrayList> innercoordinates = null;
		TIntArrayList additionalTypes = null;
		TIntObjectHashMap<String> stringNames = null;
		TIntArrayList stringOrder = null;
		long id = 0;
		
		boolean loop = true; 
		while (loop) {
			int t = codedIS.readTag();
			tag = WireFormat.getTagFieldNumber(t);
			
			
			switch (tag) {
			case 0:
				loop = false;
				break;
			case OsmandOdb.MapData.POLYGONINNERCOORDINATES_FIELD_NUMBER://4
				if (innercoordinates == null) {
					innercoordinates = new ArrayList<TIntArrayList>();
				}
				TIntArrayList polygon = new TIntArrayList();
				innercoordinates.add(polygon);
				px = tree.left & MASK_TO_READ;
				py = tree.top & MASK_TO_READ;
				size = codedIS.readRawVarint32();
				
				//
				//writeFileSdcard(dir,"size"+" "+size+"\n");
				
				old = codedIS.pushLimit(size);
				while (codedIS.getBytesUntilLimit() > 0) {
					
					int temp1 = codedIS.readSInt32();
					int temp2 = codedIS.readSInt32();
					//writeFileSdcard(dir,"temp1"+" "+temp1+"\n");
					//writeFileSdcard(dir,"temp2"+" "+temp2+"\n");
					
					int x = (temp1 << SHIFT_COORDINATES) + px;
					int y = (temp2 << SHIFT_COORDINATES) + py;
					
					//int x = (codedIS.readSInt32() << SHIFT_COORDINATES) + px;
					//int y = (codedIS.readSInt32() << SHIFT_COORDINATES) + py;
					polygon.add(x);
					polygon.add(y);
					px = x;
					py = y;
				}
				codedIS.popLimit(old);
				break;
			case OsmandOdb.MapData.ADDITIONALTYPES_FIELD_NUMBER://6
				additionalTypes = new TIntArrayList();
				int sizeL = codedIS.readRawVarint32();
				
				//
				//writeFileSdcard(dir,"ADDITIONALTYPES_FIELD sizeL"+" "+sizeL+"\n");
				old = codedIS.pushLimit(sizeL);
				while (codedIS.getBytesUntilLimit() > 0) {
					
					int temp = codedIS.readRawVarint32();
					
					//writeFileSdcard(dir," temp"+" "+temp+"\n");
					additionalTypes.add(temp);
					
					//additionalTypes.add(codedIS.readRawVarint32());
				}
				codedIS.popLimit(old);
				break;
			case OsmandOdb.MapData.TYPES_FIELD_NUMBER://7
				req.cacheTypes.clear();
				sizeL = codedIS.readRawVarint32();
				
				//writeFileSdcard(dir,"TYPES_FIELD sizeL"+" "+sizeL+"\n");
				
				
				old = codedIS.pushLimit(sizeL);
				while (codedIS.getBytesUntilLimit() > 0) {
					
					int temp = codedIS.readRawVarint32();
					
					//writeFileSdcard(dir," temp"+" "+temp+"\n");
					req.cacheTypes.add(temp);
					
					//req.cacheTypes.add(codedIS.readRawVarint32());
				}
				codedIS.popLimit(old);
				boolean accept = true;
				if (req.searchFilter != null) {
					accept = req.searchFilter.accept(req.cacheTypes, root);
				}
				if (!accept) {
					codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
					return null;
				}
				req.numberOfAcceptedObjects++;
				break;
			case OsmandOdb.MapData.ID_FIELD_NUMBER://12
				id = codedIS.readSInt64();
				
				//
				//writeFileSdcard(dir,"ID_FIELD id"+" "+id+"\n");
				break;
			case OsmandOdb.MapData.STRINGNAMES_FIELD_NUMBER://10
				stringNames = new TIntObjectHashMap<String>();
				stringOrder = new TIntArrayList();
				sizeL = codedIS.readRawVarint32();
				//
				//writeFileSdcard(dir,"STRINGNAMES_FIELD sizeL"+" "+sizeL+"\n");
				
				old = codedIS.pushLimit(sizeL);
				while (codedIS.getBytesUntilLimit() > 0) {
					int stag = codedIS.readRawVarint32();
					int pId = codedIS.readRawVarint32();
					
					//writeFileSdcard(dir,"stag"+" "+stag+"\n");
					//writeFileSdcard(dir,"pId"+" "+pId+"\n");
					
					stringNames.put(stag, ((char)pId)+"");
					stringOrder.add(stag);
				}
				codedIS.popLimit(old);
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
		BinaryMapDataObject dataObject = new BinaryMapDataObject();
		dataObject.area = area;
		dataObject.coordinates = req.cacheCoordinates.toArray();
		dataObject.objectNames = stringNames;
		dataObject.namesOrder = stringOrder;
		if (innercoordinates == null) {
			dataObject.polygonInnerCoordinates = new int[0][0];
		} else {
			dataObject.polygonInnerCoordinates = new int[innercoordinates.size()][];
			for (int i = 0; i < innercoordinates.size(); i++) {
				dataObject.polygonInnerCoordinates[i] = innercoordinates.get(i).toArray();
			}
		}
		dataObject.types = req.cacheTypes.toArray();
		if (additionalTypes != null) {
			dataObject.additionalTypes = additionalTypes.toArray();
		} else {
			dataObject.additionalTypes = new int[0];
		}
		dataObject.id = id;
		dataObject.area = area;
		dataObject.mapIndex = root;
		
		
		
		return dataObject;
	}
	
	public List<MapObject> searchAddressDataByName(SearchRequest<MapObject> req) throws IOException {
		if (req.nameQuery == null || req.nameQuery.length() == 0) {
			throw new IllegalArgumentException();
		}
		for (AddressRegion reg : addressIndexes) {
			if(reg.indexNameOffset != -1) {
				codedIS.seek(reg.indexNameOffset);
				int len = readInt();
				int old = codedIS.pushLimit(len);
				addressAdapter.searchAddressDataByName(reg, req, null);
				codedIS.popLimit(old);
			}
		}
		return req.getSearchResults();
	}
	
	public void initCategories(PoiRegion poiIndex) throws IOException {
		poiAdapter.initCategories(poiIndex);
	}
	
	public List<Amenity> searchPoiByName(SearchRequest<Amenity> req) throws IOException {
		if (req.nameQuery == null || req.nameQuery.length() == 0) {
			throw new IllegalArgumentException();
		}
		for (PoiRegion poiIndex : poiIndexes) {
			poiAdapter.initCategories(poiIndex);
			codedIS.seek(poiIndex.filePointer);
			int old = codedIS.pushLimit(poiIndex.length);
			poiAdapter.searchPoiByName(poiIndex, req);
			codedIS.popLimit(old);
		}
		return req.getSearchResults();
	}
	
	public Map<AmenityType, List<String> > searchPoiCategoriesByName(String query, Map<AmenityType, List<String> > map) throws IOException {
		if (query == null || query.length() == 0) {
			throw new IllegalArgumentException();
		}
		Collator collator = OsmAndCollator.primaryCollator();
		for (PoiRegion poiIndex : poiIndexes) {
			poiAdapter.initCategories(poiIndex);
			for (int i = 0; i < poiIndex.categories.size(); i++) {
				String cat = poiIndex.categories.get(i);
				AmenityType catType = poiIndex.categoriesType.get(i);
				if (CollatorStringMatcher.cmatches(collator, cat, query, StringMatcherMode.CHECK_STARTS_FROM_SPACE)) {
					map.put(catType, null);
				} else {
					List<String> subcats = poiIndex.subcategories.get(i);
					for (int j = 0; j < subcats.size(); j++) {
						if (CollatorStringMatcher.cmatches(collator, subcats.get(j), query, StringMatcherMode.CHECK_STARTS_FROM_SPACE)) {
							if (!map.containsKey(catType)) {
								map.put(catType, new ArrayList<String>());
							}
							List<String> list = map.get(catType);
							if (list != null) {
								list.add(subcats.get(j));
							}
						}

					}
				}
			}
		}
		return map;
	}
	
	public List<Amenity> searchPoi(SearchRequest<Amenity> req) throws IOException {
		req.numberOfVisitedObjects = 0;
		req.numberOfAcceptedObjects = 0;
		req.numberOfAcceptedSubtrees = 0;
		req.numberOfReadSubtrees = 0;
		for (PoiRegion poiIndex : poiIndexes) {
			poiAdapter.initCategories(poiIndex);
			codedIS.seek(poiIndex.filePointer);
			int old = codedIS.pushLimit(poiIndex.length);
			poiAdapter.searchPoiIndex(req.left, req.right, req.top, req.bottom, req, poiIndex);
			codedIS.popLimit(old);
		}
		log.info("Read " + req.numberOfReadSubtrees + " subtrees. Go through " + req.numberOfAcceptedSubtrees + " subtrees.");   //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
		log.info("Search poi is done. Visit " + req.numberOfVisitedObjects + " objects. Read " + req.numberOfAcceptedObjects + " objects."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		
		return req.getSearchResults();
	}
	
	public List<Amenity> searchPoi(PoiRegion poiIndex, SearchRequest<Amenity> req) throws IOException {
		req.numberOfVisitedObjects = 0;
		req.numberOfAcceptedObjects = 0;
		req.numberOfAcceptedSubtrees = 0;
		req.numberOfReadSubtrees = 0;
		
		poiAdapter.initCategories(poiIndex);
		codedIS.seek(poiIndex.filePointer);
		int old = codedIS.pushLimit(poiIndex.length);
		poiAdapter.searchPoiIndex(req.left, req.right, req.top, req.bottom, req, poiIndex);
		codedIS.popLimit(old);
		
		log.info("Search poi is done. Visit " + req.numberOfVisitedObjects + " objects. Read " + req.numberOfAcceptedObjects + " objects."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		log.info("Read " + req.numberOfReadSubtrees + " subtrees. Go through " + req.numberOfAcceptedSubtrees + " subtrees.");   //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
		
		return req.getSearchResults();
	}
		
	protected List<String> readStringTable() throws IOException{
		List<String> list = new ArrayList<String>();
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				return list;
			case OsmandOdb.StringTable.S_FIELD_NUMBER :
				list.add(codedIS.readString());
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	

	protected List<AddressRegion> getAddressIndexes() {
		return addressIndexes;
	}
	
	protected List<PoiRegion> getPoiIndexes() {
		return poiIndexes;
	}

	
	public static SearchRequest<BinaryMapDataObject> buildSearchRequest(int sleft, int sright, int stop, int sbottom, int zoom, SearchFilter searchFilter){
		return buildSearchRequest(sleft, sright, stop, sbottom, zoom, searchFilter, null);
	}
	
	
	
	public static SearchRequest<BinaryMapDataObject> buildSearchRequest(int sleft, int sright, int stop, int sbottom, int zoom, SearchFilter searchFilter, 
			ResultMatcher<BinaryMapDataObject> resultMatcher){
		SearchRequest<BinaryMapDataObject> request = new SearchRequest<BinaryMapDataObject>();
		request.left = sleft;
		request.right = sright;
		request.top = stop;
		request.bottom = sbottom;
		request.zoom = zoom;
		request.searchFilter = searchFilter;
		request.resultMatcher = resultMatcher; 
		return request;
	}
	
	public static <T> SearchRequest<T> buildAddressRequest(ResultMatcher<T> resultMatcher){
		SearchRequest<T> request = new SearchRequest<T>();
		request.resultMatcher = resultMatcher;
		return request;
	}
	
	
	
	public static <T> SearchRequest<T> buildAddressByNameRequest(ResultMatcher<T> resultMatcher, String nameRequest){
		SearchRequest<T> request = new SearchRequest<T>();
		request.resultMatcher = resultMatcher;
		request.nameQuery = nameRequest;
		return request;
	}
	
	public static SearchRequest<Amenity> buildSearchPoiRequest(List<Location> route, double radius,
			SearchPoiTypeFilter poiTypeFilter, ResultMatcher<Amenity> resultMatcher) {
		SearchRequest<Amenity> request = new SearchRequest<Amenity>();
		float coeff = (float) (radius / MapUtils.getTileDistanceWidth(SearchRequest.ZOOM_TO_SEARCH_POI)); 
		TIntObjectHashMap<List<Location>> zooms = new TIntObjectHashMap<List<Location>>();
		for(int i = 1; i < route.size(); i++) {
			Location cr = route.get(i);
			Location pr = route.get(i - 1);
			double tx = MapUtils.getTileNumberX(SearchRequest.ZOOM_TO_SEARCH_POI, cr.getLongitude());
			double ty = MapUtils.getTileNumberY(SearchRequest.ZOOM_TO_SEARCH_POI, cr.getLatitude());
			double px = MapUtils.getTileNumberX(SearchRequest.ZOOM_TO_SEARCH_POI, pr.getLongitude());
			double py = MapUtils.getTileNumberY(SearchRequest.ZOOM_TO_SEARCH_POI, pr.getLatitude());
			double topLeftX = Math.min(tx, px) - coeff;
			double topLeftY = Math.min(ty, py) - coeff;
			double bottomRightX = Math.max(tx, px) + coeff;
			double bottomRightY = Math.max(ty, py) + coeff;
			for(int x = (int) topLeftX; x <= bottomRightX; x++) {
				for(int y = (int) topLeftY; y <= bottomRightY; y++) {
					int hash = (x << SearchRequest.ZOOM_TO_SEARCH_POI) + y;
					if(!zooms.containsKey(hash)) {
						zooms.put(hash, new LinkedList<Location>());
					}
					List<Location> ll = zooms.get(hash);
					ll.add(pr);
					ll.add(cr);
				}
			}
			
		}
		int sleft = 0, sright = Integer.MAX_VALUE, stop = 0, sbottom = Integer.MAX_VALUE;
		for(int vl : zooms.keys()) {
			int x = (vl >> SearchRequest.ZOOM_TO_SEARCH_POI) << (31 - SearchRequest.ZOOM_TO_SEARCH_POI);
			int y = (vl & ((1 << SearchRequest.ZOOM_TO_SEARCH_POI) -1)) << (31 - SearchRequest.ZOOM_TO_SEARCH_POI);
			sleft = Math.min(x, sleft);
			stop = Math.min(y, stop);
			sbottom = Math.max(y, sbottom);
			sright = Math.max(x, sright);
		}
		request.radius = radius;
		request.left = sleft;
		request.zoom = -1;
		request.right = sright;
		request.top = stop;
		request.bottom = sbottom;
		request.tiles = zooms;
		request.poiTypeFilter = poiTypeFilter;
		request.resultMatcher = resultMatcher;
		return request;
	}
	
	public static SearchRequest<Amenity> buildSearchPoiRequest(int sleft, int sright, int stop, int sbottom, int zoom, 
			SearchPoiTypeFilter poiTypeFilter, ResultMatcher<Amenity> matcher){
		SearchRequest<Amenity> request = new SearchRequest<Amenity>();
		request.left = sleft;
		request.right = sright;
		request.top = stop;
		request.bottom = sbottom;
		request.zoom = zoom;
		request.poiTypeFilter = poiTypeFilter;
		request.resultMatcher = matcher;
		
		return request;
	}
	
	public static SearchRequest<RouteDataObject> buildSearchRouteRequest(int sleft, int sright, int stop, int sbottom,  
			ResultMatcher<RouteDataObject> matcher){
		SearchRequest<RouteDataObject> request = new SearchRequest<RouteDataObject>();
		request.left = sleft;
		request.right = sright;
		request.top = stop;
		request.bottom = sbottom;
		request.resultMatcher = matcher;
		
		return request;
	}
	
	
	
	
	public static SearchRequest<Amenity> buildSearchPoiRequest(int x, int y, String nameFilter, int sleft, int sright, int stop, int sbottom, ResultMatcher<Amenity> resultMatcher){
		SearchRequest<Amenity> request = new SearchRequest<Amenity>();
		request.x = x;
		request.y = y;
		request.left = sleft;
		request.right = sright;
		request.top = stop;
		request.bottom = sbottom;
		request.resultMatcher = resultMatcher;
		request.nameQuery = nameFilter;
		return request;
	}
	
	
	public static SearchRequest<TransportStop> buildSearchTransportRequest(int sleft, int sright, int stop, int sbottom, int limit, List<TransportStop> stops){
		SearchRequest<TransportStop> request = new SearchRequest<TransportStop>();
		if (stops != null) {
			request.searchResults = stops;
		}
		request.stringTable = new TIntObjectHashMap<String>();
		request.left = sleft >> (31 - TRANSPORT_STOP_ZOOM);
		request.right = sright >> (31 - TRANSPORT_STOP_ZOOM);
		request.top = stop >> (31 - TRANSPORT_STOP_ZOOM);
		request.bottom = sbottom >> (31 - TRANSPORT_STOP_ZOOM);
		request.limit = limit;
		return request;
	}
	
	public void close() throws IOException{
		if(codedIS != null){
			raf.close();
			codedIS = null;
			mapIndexes.clear();
			addressIndexes.clear();
			transportIndexes.clear();
		}
	}
	
	public static interface SearchFilter {
		
		public boolean accept(TIntArrayList types, MapIndex index);
		
	}
	
	public static interface SearchPoiTypeFilter {
		
		public boolean accept(AmenityType type, String subcategory);
		
	}
	
	public static class SearchRequest<T> {
		public final static int ZOOM_TO_SEARCH_POI = 16; 
		private List<T> searchResults = new ArrayList<T>();
		private boolean land = false;
		private boolean ocean = false;
		
		private ResultMatcher<T> resultMatcher;
		
		// 31 zoom tiles
		// common variables
		int x = 0;
		int y = 0;
		int left = 0;
		int right = 0;
		int top = 0;
		int bottom = 0;
		
		int zoom = 15;
		int limit = -1;

		// search on the path
		// stores tile of 16 index and pairs (even length always) of points intersecting tile
		TIntObjectHashMap<List<Location>> tiles = null;
		double radius = -1;
		
		
		String nameQuery = null;

		SearchFilter searchFilter = null;
		
		SearchPoiTypeFilter poiTypeFilter = null;
		
		// internal read information
		TIntObjectHashMap<String> stringTable = null;
		
		// cache information
		TIntArrayList cacheCoordinates = new TIntArrayList();
		TIntArrayList cacheTypes = new TIntArrayList();
		
		
		// TRACE INFO
		int numberOfVisitedObjects = 0;
		int numberOfAcceptedObjects = 0;
		int numberOfReadSubtrees = 0;
		int numberOfAcceptedSubtrees = 0;
		boolean interrupted = false;
		
		protected SearchRequest(){
		}
		
		public int getTileHashOnPath(double lat, double lon) {
			int x = (int) MapUtils.getTileNumberX(SearchRequest.ZOOM_TO_SEARCH_POI, lon);
			int y = (int) MapUtils.getTileNumberY(SearchRequest.ZOOM_TO_SEARCH_POI, lat);
			return (x << SearchRequest.ZOOM_TO_SEARCH_POI) | y;
		}
		
		
		public boolean publish(T obj){
			if(resultMatcher == null || resultMatcher.publish(obj)){
				searchResults.add(obj);
				return true;
			}
			return false;
		}
		
		protected void publishOceanTile(boolean ocean){
			if(ocean) {
				this.ocean = true;
			} else {
				this.land = true;
			}
		}
		
		public List<T> getSearchResults() {
			return searchResults;
		}
		
		public void setInterrupted(boolean interrupted) {
			this.interrupted = interrupted;
		}
		
		public boolean limitExceeded() {
			return limit != -1 && searchResults.size() > limit;
		}
		public boolean isCancelled() {
			if(this.interrupted){
				return interrupted;
			}
			if(resultMatcher != null){
				return resultMatcher.isCancelled();
			}
			return false;
		}
		
		public boolean isOcean() {
			return ocean;
		}
		
		public boolean isLand() {
			return land;
		}
		
		public boolean intersects(int l, int t, int r, int b){
			return r >= left && l <= right && t <= bottom && b >= top;
		}
		
		public boolean contains(int l, int t, int r, int b){
			return r <= right && l >= left && b <= bottom && t >= top;
		}
		
		public int getLeft() {
			return left;
		}
		
		public int getRight() {
			return right;
		}
		
		public int getBottom() {
			return bottom;
		}
		
		public int getTop() {
			return top;
		}
		
		public int getZoom() {
			return zoom;
		}
		
		public void clearSearchResults(){
			// recreate whole list to allow GC collect old data 
			searchResults = new ArrayList<T>();
			cacheCoordinates.clear();
			cacheTypes.clear();
			land = false;
			ocean = false;
			numberOfVisitedObjects = 0;
			numberOfAcceptedObjects = 0;
			numberOfReadSubtrees = 0;
			numberOfAcceptedSubtrees = 0;
		}
	}
	
	
	public static class MapIndex extends BinaryIndexPart {
		List<MapRoot> roots = new ArrayList<MapRoot>();
		
		Map<String, Map<String, Integer> > encodingRules = new HashMap<String, Map<String, Integer> >();
		TIntObjectMap<TagValuePair> decodingRules = new TIntObjectHashMap<TagValuePair>();
		public int nameEncodingType = 0;
		public int nameEnEncodingType = -1;
		public int refEncodingType = -1;
		public int coastlineEncodingType = -1;
		public int coastlineBrokenEncodingType = -1;
		public int landEncodingType = -1;
		public int onewayAttribute = -1;
		public int onewayReverseAttribute = -1;
		public TIntHashSet positiveLayers = new TIntHashSet(2);
		public TIntHashSet negativeLayers = new TIntHashSet(2);
		
		public Integer getRule(String t, String v){
			Map<String, Integer> m = encodingRules.get(t);
			if(m != null){
				return m.get(v);
			}
			return null;
		}
		
		public List<MapRoot> getRoots() {
			return roots;
		}
		
		public TagValuePair decodeType(int type){
			return decodingRules.get(type);
		}

		public void finishInitializingTags() {
			int free = decodingRules.size() * 2 + 1;
			coastlineBrokenEncodingType = free++;
			initMapEncodingRule(0, coastlineBrokenEncodingType, "natural", "coastline_broken");
			if(landEncodingType == -1){
				landEncodingType = free++;
				initMapEncodingRule(0, landEncodingType, "natural", "land");
			}
		}
		
		public boolean isRegisteredRule(int id) {
			return decodingRules.containsKey(id);
		}
		
		public void initMapEncodingRule(int type, int id, String tag, String val) {
			if(!encodingRules.containsKey(tag)){
				encodingRules.put(tag, new HashMap<String, Integer>());
			}
			encodingRules.get(tag).put(val, id);
			if(!decodingRules.containsKey(id)){
				decodingRules.put(id, new TagValuePair(tag, val, type));
			}
			
			if("name".equals(tag)){
				nameEncodingType = id;
			} else if("natural".equals(tag) && "coastline".equals(val)){
				coastlineEncodingType = id;
			} else if("natural".equals(tag) && "land".equals(val)){
				landEncodingType = id;
			} else if("oneway".equals(tag) && "yes".equals(val)){
				onewayAttribute = id;
			} else if("oneway".equals(tag) && "-1".equals(val)){
				onewayReverseAttribute = id;
			} else if("ref".equals(tag)){
				refEncodingType = id;
			} else if("name:en".equals(tag)){
				nameEnEncodingType = id;
			} else if("tunnel".equals(tag)){
				negativeLayers.add(id);
			} else if("bridge".equals(tag)){
				positiveLayers.add(id);
			} else if("layer".equals(tag)){
				if(val != null && !val.equals("0") && val.length() > 0) {
					if(val.startsWith("-")) {
						negativeLayers.add(id);
					} else {
						positiveLayers.add(id);
					}
				}
			}
		}

		public boolean isBaseMap(){
			return name != null && name.toLowerCase().contains(BASEMAP_NAME);
		}
	}
	
	public static class TagValuePair {
		public String tag;
		public String value;
		public int additionalAttribute;
		
		
		public TagValuePair(String tag, String value, int additionalAttribute) {
			super();
			this.tag = tag;
			this.value = value;
			this.additionalAttribute = additionalAttribute;
		}
		
		public boolean isAdditional(){
			return additionalAttribute % 2 == 1;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + additionalAttribute;
			result = prime * result + ((tag == null) ? 0 : tag.hashCode());
			result = prime * result + ((value == null) ? 0 : value.hashCode());
			return result;
		}
		
		public String toSimpleString(){
			if(value == null){
				return tag;
			}
			return tag+"-"+value;
		}
		
		@Override
		public String toString() {
			return "TagValuePair : " + tag + " - " + value;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			TagValuePair other = (TagValuePair) obj;
			if (additionalAttribute != other.additionalAttribute)
				return false;
			if (tag == null) {
				if (other.tag != null)
					return false;
			} else if (!tag.equals(other.tag))
				return false;
			if (value == null) {
				if (other.value != null)
					return false;
			} else if (!value.equals(other.value))
				return false;
			return true;
		}
		
	}
	
	
	public static class MapRoot extends MapTree {
		int minZoom = 0;
		int maxZoom = 0;
		
		
		public int getMinZoom() {
			return minZoom;
		}
		public int getMaxZoom() {
			return maxZoom;
		}
		
		private List<MapTree> trees = null;
	}
	
	private static class MapTree {
		int filePointer = 0;
		int length = 0;
		
		long mapDataBlock = 0;
		Boolean ocean = null;
		
		int left = 0;
		int right = 0;
		int top = 0;
		int bottom = 0;
		
		public int getLeft() {
			return left;
		}
		public int getRight() {
			return right;
		}
		public int getTop() {
			return top;
		}
		public int getBottom() {
			return bottom;
		}
		
		public int getLength() {
			return length;
		}
		public int getFilePointer() {
			return filePointer;
		}
		
		@Override
		public String toString(){
			return "Top Lat " + ((float) MapUtils.get31LatitudeY(top)) + " lon " + ((float) MapUtils.get31LongitudeX(left))
					+ " Bottom lat " + ((float) MapUtils.get31LatitudeY(bottom)) + " lon " + ((float) MapUtils.get31LongitudeX(right));
		}
		
	}

	
	private static boolean testMapSearch = false;
	private static boolean testAddressSearch = false;
	private static boolean testPoiSearch = false;
	private static boolean testTransportSearch = false;
	private static int sleft = MapUtils.get31TileNumberX(6.3);
	private static int sright = MapUtils.get31TileNumberX(6.5);
	private static int stop = MapUtils.get31TileNumberY(49.9);
	private static int sbottom = MapUtils.get31TileNumberY(49.7);
	private static int szoom = 15;
	
	private static void println(String s){
		System.out.println(s);
	}
	
	public static void main(String[] args) throws IOException {
		RandomAccessFile raf = new RandomAccessFile("", "r");
		
		String dir = Environment.getExternalStorageDirectory()+"/";
		File f =new File(dir,"buffer2.txt");
		
		String bufferdir = dir+"buffer2.txt";
		if(!f.exists()){
			
			f.createNewFile();
			//f.delete();
		}
		 try{ 

		        //FileOutputStream fout = openFileOutput(fileName, MODE_PRIVATE);

		       FileOutputStream fout = new FileOutputStream(bufferdir,true);
		       String message = "test enter of init()  main(String[] args)"+"\n";
		       
		       byte [] bytes = message.getBytes(); 
		       fout.write(bytes);
		       
		       fout.close(); 
		    
		        } 

		       catch(Exception e){
		    	
		        e.printStackTrace(); 

		       } 
		 
		BinaryMapIndexReader reader = new BinaryMapIndexReader(raf);
		println("VERSION " + reader.getVersion()); //$NON-NLS-1$
		long time = System.currentTimeMillis();

		if (testMapSearch) {
			testMapSearch(reader);
		}
		if(testAddressSearch) {
			testAddressSearchByName(reader);
			testAddressSearch(reader);
		}
		if(testTransportSearch) {
			testTransportSearch(reader);
		}

		if (testPoiSearch) {
			PoiRegion poiRegion = reader.getPoiIndexes().get(0);
			testPoiSearch(reader, poiRegion);
			testPoiSearchByName(reader);
			testSearchOnthePath(reader);
		}

		println("MEMORY " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())); //$NON-NLS-1$
		println("Time " + (System.currentTimeMillis() - time)); //$NON-NLS-1$
	}

	private static void testSearchOnthePath(BinaryMapIndexReader reader) throws IOException {
		float radius = 1000;
		long now = System.currentTimeMillis();
		println("Searching poi on the path...");
		final List<Location> locations = readGPX(new File(
				""));
		SearchRequest<Amenity> req = buildSearchPoiRequest(locations, radius, new SearchPoiTypeFilter() {
			@Override
			public boolean accept(AmenityType type, String subcategory) {
				if (type == AmenityType.SHOP && subcategory.contains("super")) {
					return true;
				}
				return false;
			}

		}, null);
		req.zoom = -1;
		List<Amenity> results = reader.searchPoi(req);
		int k = 0;
		println("Search done in " + (System.currentTimeMillis() - now) + " ms ");
		now = System.currentTimeMillis();

		for (Amenity a : results) {
			final float dds = dist(a.getLocation(), locations);
			if (dds <= radius) {
				println("+ " + a.getType() + " " + a.getSubType() + " Dist " + dds + " (=" + (float)a.getRoutePoint().deviateDistance + ") " + a.getName() + " " + a.getLocation());
				k++;
			} else {
				println(a.getType() + " " + a.getSubType() + " Dist " + dds + " " + a.getName() + " " + a.getLocation());
			}
		}
		println("Filtered in " + (System.currentTimeMillis() - now) + "ms " + k + " of " + results.size());
	}
	
	private static float dist(LatLon l, List<Location> locations) {
		float dist = Float.POSITIVE_INFINITY;
		for(int i = 1; i < locations.size(); i++){
			dist = Math.min(dist,(float) MapUtils.getOrthogonalDistance(l.getLatitude(), l.getLongitude(), 
					locations.get(i-1).getLatitude(), locations.get(i-1).getLongitude(),
					locations.get(i).getLatitude(), locations.get(i).getLongitude()));
		}
		return dist;
	}

	private static Reader getUTF8Reader(InputStream f) throws IOException {
		BufferedInputStream bis = new BufferedInputStream(f);
		assert bis.markSupported();
		bis.mark(3);
		boolean reset = true;
		byte[] t = new byte[3];
		bis.read(t);
		if (t[0] == ((byte) 0xef) && t[1] == ((byte) 0xbb) && t[2] == ((byte) 0xbf)) {
			reset = false;
		}
		if (reset) {
			bis.reset();
		}
		return new InputStreamReader(bis, "UTF-8");
	}
	
	private static List<Location> readGPX(File f) {
			List<Location> res = new ArrayList<Location>();
			try {
				StringBuilder content = new StringBuilder();
				BufferedReader reader = new BufferedReader(getUTF8Reader(new FileInputStream(f)));
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				DocumentBuilder dom = factory.newDocumentBuilder();
//				{
//					String s = null;
//					boolean fist = true;
//					while ((s = reader.readLine()) != null) {
//						if (fist) {
//							fist = false;
//						}
//						content.append(s).append("\n");
//					}
//				}
//				Document doc = dom.parse(new InputSource(new StringReader(content.toString())));
				Document doc = dom.parse(new InputSource(reader));
				NodeList list = doc.getElementsByTagName("trkpt");
				Way w = new Way(-1);
				for (int i = 0; i < list.getLength(); i++) {
					Element item = (Element) list.item(i);
					try {
						double lon = Double.parseDouble(item.getAttribute("lon"));
						double lat = Double.parseDouble(item.getAttribute("lat"));
						final Location o = new Location("");
						o.setLatitude(lat);
						o.setLongitude(lon);
						res.add(o);
					} catch (NumberFormatException e) {
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			} catch (ParserConfigurationException e) {
				throw new RuntimeException(e);
			} catch (SAXException e) {
				throw new RuntimeException(e);
			}
			return res;
		}

	private static void testPoiSearchByName(BinaryMapIndexReader reader) throws IOException {
		println("Searching by name...");
		SearchRequest<Amenity> req = buildSearchPoiRequest(0, 0, "roch",  
				0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, null);
		reader.searchPoiByName(req);
		for (Amenity a : req.getSearchResults()) {
			println(a.getType() + " " + a.getSubType() + " " + a.getName() + " " + a.getLocation());
		}
	}

	private static void testPoiSearch(BinaryMapIndexReader reader, PoiRegion poiRegion) throws IOException {
		println(poiRegion.leftLongitude + " " + poiRegion.rightLongitude + " " + poiRegion.bottomLatitude + " "
				+ poiRegion.topLatitude);
		for (int i = 0; i < poiRegion.categories.size(); i++) {
			println(poiRegion.categories.get(i));
			println(" " + poiRegion.subcategories.get(i));
		}

		SearchRequest<Amenity> req = buildSearchPoiRequest(sleft, sright, stop, sbottom, -1, new SearchPoiTypeFilter() {
			@Override
			public boolean accept(AmenityType type, String subcategory) {
				return true;
			}

		}, null);
		List<Amenity> results = reader.searchPoi(req);
		for (Amenity a : results) {
			println(a.getType() + " " + a.getSubType() + " " + a.getName() + " " + a.getLocation());
		}
	}

	private static void testTransportSearch(BinaryMapIndexReader reader) throws IOException {
		// test transport
		for (TransportIndex i : reader.transportIndexes) {
			println("Transport bounds : " + i.left + " " + i.right + " " + i.top + " " + i.bottom);
		}
		{
			for (TransportStop s : reader.searchTransportIndex(buildSearchTransportRequest(sleft, sright, stop, sbottom, 15, null))) {
				println(s.getName());
				TIntObjectHashMap<TransportRoute> routes = reader.getTransportRoutes(s.getReferencesToRoutes());
				for (net.osmand.data.TransportRoute route : routes.valueCollection()) {
					println(" " + route.getRef() + " " + route.getName() + " " + route.getDistance() + " "
							+ route.getAvgBothDistance());
				}
			}
		}
		{
			for (TransportStop s : reader.searchTransportIndex(buildSearchTransportRequest(sleft, sright, stop, sbottom, 16, null))) {
				println(s.getName());
				TIntObjectHashMap<TransportRoute> routes = reader.getTransportRoutes(s.getReferencesToRoutes());
				for (net.osmand.data.TransportRoute  route : routes.valueCollection()) {
					println(" " + route.getRef() + " " + route.getName() + " " + route.getDistance() + " "
							+ route.getAvgBothDistance());
				}
			}
		}
	}
	
	private static void updateFrequence(Map<String, Integer> street , String key){
		if(!street.containsKey(key)){
			street.put(key, 1);
		} else {
			street.put(key, street.get(key) + 1);
		}
		
	}

	int readIndexedStringTable(Collator instance, String query, String prefix, TIntArrayList list, int charMatches) throws IOException {
		String key = null;
		while(true){
			int t = codedIS.readTag();
			int tag = WireFormat.getTagFieldNumber(t);
			switch (tag) {
			case 0:
				return charMatches;
			case OsmandOdb.IndexedStringTable.KEY_FIELD_NUMBER :
				key = codedIS.readString();
				if(prefix.length() > 0){
					key = prefix + key;
				}
				// check query is part of key (the best matching)
				if(CollatorStringMatcher.cmatches(instance, key, query, StringMatcherMode.CHECK_ONLY_STARTS_WITH)){
					if(query.length() >= charMatches){
						if(query.length() > charMatches){
							charMatches = query.length();
							list.clear();
						}
					} else {
						key = null;
					}
					// check key is part of query
				} else if (CollatorStringMatcher.cmatches(instance, query, key, StringMatcherMode.CHECK_ONLY_STARTS_WITH)) {
					if (key.length() >= charMatches) {
						if (key.length() > charMatches) {
							charMatches = key.length();
							list.clear();
						}
					} else {
						key = null;
					}
				} else {
					key = null;
				}
				break;
			case OsmandOdb.IndexedStringTable.VAL_FIELD_NUMBER :
				int val = readInt();
				if (key != null) {
					list.add(val);
				}
				break;
			case OsmandOdb.IndexedStringTable.SUBTABLES_FIELD_NUMBER :
				int len = codedIS.readRawVarint32();
				int oldLim = codedIS.pushLimit(len);
				if (key != null) {
					charMatches = readIndexedStringTable(instance, query, key, list, charMatches);
				} else {
					codedIS.skipRawBytes(codedIS.getBytesUntilLimit());
				}
				codedIS.popLimit(oldLim);
				break;
			default:
				skipUnknownField(t);
				break;
			}
		}
	}
	
	private static void testAddressSearchByName(BinaryMapIndexReader reader) throws IOException {
		SearchRequest<MapObject> req = buildAddressByNameRequest(new ResultMatcher<MapObject>() {
			@Override
			public boolean publish(MapObject object) {
				if(object instanceof Street) {
					System.out.println(object + " " + ((Street) object).getCity());
				} else {
					System.out.println(object);
				}
				return false;
			}
			@Override
			public boolean isCancelled() {
				return false;
			}
		}, "lux");
		reader.searchAddressDataByName(req);
	}
	
	private static void testAddressSearch(BinaryMapIndexReader reader) throws IOException {
		// test address index search
		String reg = reader.getRegionNames().get(0);
		final Map<String, Integer> streetFreq = new HashMap<String, Integer>();
		List<City> cs = reader.getCities(reg, null, BinaryMapAddressReaderAdapter.CITY_TOWN_TYPE);
		for(City c : cs){
			int buildings = 0;
			reader.preloadStreets(c, null);
			for(Street s : c.getStreets()){
				updateFrequence(streetFreq, s.getName());
				reader.preloadBuildings(s, buildAddressRequest((ResultMatcher<Building>) null));
				buildings += s.getBuildings().size();
			}
			println(c.getName() + " " + c.getLocation() + " " + c.getStreets().size() + " " + buildings + " " + c.getEnName());
		}
//		int[] count = new int[1];
		List<City> villages = reader.getCities(reg, buildAddressRequest((ResultMatcher<City>) null), BinaryMapAddressReaderAdapter.VILLAGES_TYPE);
		for(City v : villages) {
			reader.preloadStreets(v,  null);
			for(Street s : v.getStreets()){
				updateFrequence(streetFreq, s.getName());
			}
		}
		System.out.println("Villages " + villages.size());
		
		List<String> sorted = new ArrayList<String>(streetFreq.keySet());
		Collections.sort(sorted, new Comparator<String>() {
			@Override
			public int compare(String o1, String o2) {
				return - streetFreq.get(o1) + streetFreq.get(o2);
			}
		});
		System.out.println(streetFreq.size());
		for(String s : sorted) {
			System.out.println(s + "   " + streetFreq.get(s));
			if(streetFreq.get(s) < 10){
				break;
			}
		}
		
	}

	private static void testMapSearch(BinaryMapIndexReader reader) throws IOException {
		println(reader.mapIndexes.get(0).encodingRules + "");
		println("SEARCH " + sleft + " " + sright + " " + stop + " " + sbottom);
		
		String dir = Environment.getExternalStorageDirectory()+"/";
		File f =new File(dir,"buffer.txt");
		
		String bufferdir = dir+"buffer.txt";
		if(!f.exists()){
			
			f.createNewFile();
			//f.delete();
		}
		 try{ 

		        //FileOutputStream fout = openFileOutput(fileName, MODE_PRIVATE);

		       FileOutputStream fout = new FileOutputStream(bufferdir,true);
		       String message = "testMapSearch:2454";
		       
		       byte [] bytes = message.getBytes(); 
		       fout.write(bytes);
		       
		       fout.close(); 
		    
		        } 

		       catch(Exception e){
		    	
		        e.printStackTrace(); 

		       } 
		 

		reader.searchMapIndex(buildSearchRequest(sleft, sright, stop, sbottom, szoom, null, new ResultMatcher<BinaryMapDataObject>() {
			
			@Override
			public boolean publish(BinaryMapDataObject obj) {
				
				StringBuilder b = new StringBuilder();
				b.append(obj.area? "Area" : (obj.getPointsLength() > 1? "Way" : "Point"));
				int[] types = obj.getTypes();
				b.append(" types [");
				for(int j = 0; j<types.length; j++){
					if(j > 0) {
						b.append(", ");
					}
					TagValuePair pair = obj.getMapIndex().decodeType(types[j]);
					if(pair == null) {
						throw new NullPointerException("Type " + types[j] + "was not found");
					}
					b.append(pair.toSimpleString()).append("(").append(types[j]).append(")");
				}
				b.append("]");
				if(obj.getAdditionalTypes() != null && obj.getAdditionalTypes().length > 0){
					b.append(" add_types [");
					for(int j = 0; j<obj.getAdditionalTypes().length; j++){
						if(j > 0) {
							b.append(", ");
						}
						TagValuePair pair = obj.getMapIndex().decodeType(obj.getAdditionalTypes()[j]);
						if(pair == null) {
							throw new NullPointerException("Type " + obj.getAdditionalTypes()[j] + "was not found");
						}
						b.append(pair.toSimpleString()).append("(").append(obj.getAdditionalTypes()[j]).append(")");
						
					}
					b.append("]");
				}
				TIntObjectHashMap<String> names = obj.getObjectNames();
				if(names != null && !names.isEmpty()) {
					b.append(" Names [");
					int[] keys = names.keys();
					for(int j = 0; j<keys.length; j++){
						if(j > 0) {
							b.append(", ");
						}
						TagValuePair pair = obj.getMapIndex().decodeType(keys[j]);
						if(pair == null) {
							throw new NullPointerException("Type " + keys[j] + "was not found");
						}
						b.append(pair.toSimpleString()).append("(").append(keys[j]).append(")");
						b.append(" - ").append(names.get(keys[j]));
					}
					b.append("]");
				}
				
				b.append(" id ").append((obj.getId() >> 1));
				b.append(" lat/lon : ");
				for(int i=0; i<obj.getPointsLength(); i++) {
					float x = (float) MapUtils.get31LongitudeX(obj.getPoint31XTile(i));
					float y = (float) MapUtils.get31LatitudeY(obj.getPoint31YTile(i));
					b.append(x).append(" / ").append(y).append(" , ");
				}
				println(b.toString());
				return false;
			}
			
			@Override
			public boolean isCancelled() {
				return false;
			}
		}));
	}
	
	
	public List<RouteSubregion> searchRouteIndexTree(SearchRequest<?> req, List<RouteSubregion> list) throws IOException {
		req.numberOfVisitedObjects = 0;
		req.numberOfAcceptedObjects = 0;
		req.numberOfAcceptedSubtrees = 0;
		req.numberOfReadSubtrees = 0;
		if (routeAdapter != null) {
			routeAdapter.initRouteTypesIfNeeded(req, list);
			return routeAdapter.searchRouteRegionTree(req, list,
					new ArrayList<BinaryMapRouteReaderAdapter.RouteSubregion>());
		}
		return Collections.emptyList();
	}

	public void loadRouteIndexData(List<RouteSubregion> toLoad, ResultMatcher<RouteDataObject> matcher) throws IOException {
		if(routeAdapter != null){
			routeAdapter.loadRouteRegionData(toLoad, matcher);
		}
	}
	
	public List<RouteDataObject> loadRouteIndexData(RouteSubregion rs) throws IOException {
		if(routeAdapter != null){
			return routeAdapter.loadRouteRegionData(rs);
		}
		return Collections.emptyList();
	}
	
	public void initRouteRegion(RouteRegion routeReg) throws IOException {
		if(routeAdapter != null){
			routeAdapter.initRouteRegion(routeReg);
		}
	}

	
	
}