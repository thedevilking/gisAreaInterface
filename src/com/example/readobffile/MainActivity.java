package com.example.readobffile;

import gnu.trove.list.array.TIntArrayList;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.osmand.IndexConstants;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.binary.BinaryMapIndexReader.TagValuePair;
import net.osmand.plus.render.RendererRegistry;
import net.osmand.render.RenderingRuleSearchRequest;
import net.osmand.render.RenderingRulesStorage;
import net.osmand.util.MapUtils;
import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;






public class MainActivity<OsmandApplication> extends Activity {
	
	static boolean alive=true;
	
	
	private Map<String, BinaryMapIndexReader> files = new ConcurrentHashMap<String, BinaryMapIndexReader>();
	private SearchRequest<BinaryMapDataObject> searchRequest;
	
	//private final OsmandApplication context;
	RendererRegistry rendererRegistry = new RendererRegistry();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        //开启写日志线程
        SaveObjectInfo.getInstance();
        
        //startApplication();
        Log.d("location", "mark:1");
        startApplicationBackground();
        
        //创建自己的需求范围
        Log.d("location", "mark:2");
        /*int leftX = MapUtils.get31TileNumberX(115);
		int rightX = MapUtils.get31TileNumberX(118);
		int bottomY = MapUtils.get31TileNumberY(36);
		int topY = MapUtils.get31TileNumberY(41);*/
        int leftX = MapUtils.get31TileNumberX(116.3665);
		int rightX = MapUtils.get31TileNumberX(117.8641);
		int bottomY = MapUtils.get31TileNumberY(39.3938);
		int topY = MapUtils.get31TileNumberY(40.0344);
		
		final int zoom = 15;
		 Log.d("location", "mark:3");
		//OsmandApplication app = ((OsmandApplication) context.getApplicationContext());
		//boolean nightMode = app.getDaynightHelper().isNightMode();
		// boolean moreDetail = prefs.SHOW_MORE_MAP_DETAIL.get();
		//OsmandApplication app = (OsmandApplication) rendererRegistry.getCurrentSelectedRenderer();
		//RenderingRulesStorage storage = app.getRendererRegistry().getCurrentSelectedRenderer();
		
		RenderingRulesStorage storage = rendererRegistry.getCurrentSelectedRenderer();
		final RenderingRuleSearchRequest renderingReq = new RenderingRuleSearchRequest(storage);
		 Log.d("location", "mark:4");
		
		BinaryMapIndexReader.SearchFilter searchFilter = new BinaryMapIndexReader.SearchFilter() {
			@Override
			public boolean accept(TIntArrayList types, BinaryMapIndexReader.MapIndex root) {
				for (int j = 0; j < types.size(); j++) {
					int type = types.get(j);
					TagValuePair pair = root.decodeType(type);
					if (pair != null) {
						// TODO is it fast enough ?
						for (int i = 1; i <= 3; i++) {
							renderingReq.setIntFilter(renderingReq.ALL.R_MINZOOM, zoom);
							renderingReq.setStringFilter(renderingReq.ALL.R_TAG, pair.tag);
							renderingReq.setStringFilter(renderingReq.ALL.R_VALUE, pair.value);
							if (renderingReq.search(i, false)) {
								return true;
							}
						}
						renderingReq.setStringFilter(renderingReq.ALL.R_TAG, pair.tag);
						renderingReq.setStringFilter(renderingReq.ALL.R_VALUE, pair.value);
						if (renderingReq.search(RenderingRulesStorage.TEXT_RULES, false)) {
							return true;
						}
					}
				}
				return false;
			}

		};
		 Log.d("location", "mark:5");
        MapIndex mi = null;
		searchRequest = BinaryMapIndexReader.buildSearchRequest(leftX, rightX, topY, bottomY, zoom, searchFilter);
		
		 Log.d("location", "mark:6");
		for (BinaryMapIndexReader c : files.values()) {
			boolean basemap = c.isBasemap();
			searchRequest.clearSearchResults();
			List<BinaryMapDataObject> res;
			try {
				res = c.searchMapIndex(searchRequest);
			} catch (IOException e) {
			
			}
		}
		Log.d("location", "mark:7");
        
    }
    
    public RendererRegistry getRendererRegistry() {
		return rendererRegistry;
	}
    
    private void startApplicationBackground() {

		long val = System.currentTimeMillis();
		
		String dir = Environment.getExternalStorageDirectory()+"/osmand/";
		
		ArrayList<File> files = new ArrayList<File>();
		File appPath = new File(dir);
		collectFiles(appPath, IndexConstants.BINARY_MAP_INDEX_EXT, files);
//		if(OsmandPlugin.getEnabledPlugin(SRTMPlugin.class) != null) {
//			collectFiles(context.getAppPath(IndexConstants.SRTM_INDEX_DIR), IndexConstants.BINARY_MAP_INDEX_EXT, files);
//		}
//		List<String> warnings = new ArrayList<String>();
//		renderer.clearAllResources();
//		CachedOsmandIndexes cachedOsmandIndexes = new CachedOsmandIndexes();
//		File indCache = context.getAppPath(INDEXES_CACHE);
//		if (indCache.exists()) {
//			try {
//				cachedOsmandIndexes.readFromFile(indCache, CachedOsmandIndexes.VERSION);
//				NativeOsmandLibrary nativeLib = NativeOsmandLibrary.getLoadedLibrary();
//				if (nativeLib != null) {
//					nativeLib.initCacheMapFile(indCache.getAbsolutePath());
//				}
//			} catch (Exception e) {
//				log.error(e.getMessage(), e);
//			}
//		}
		
		//
//		String ss = Environment.getExternalStorageDirectory()+"/";
//		String dir = ss+"Treelayertest.txt";
//		File file = new File(ss,"Treelayertest.txt");
//		if(!file.exists()){
//			try {
//				file.createNewFile();
//			} catch (IOException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		}
//		
		
		int count = 0;
		for (File f : files) {
			try {
				BinaryMapIndexReader index = getReader(f);
				
				if (index != null) {
					initializeNewResource( f, index);
				}
			}catch (IOException e) {
				
			}
		}
			
	
    	
    }
    private List<File> collectFiles(File dir, String ext, List<File> files) {
		if(dir.exists() && dir.canRead()) {
			File[] lf = dir.listFiles();
			if(lf == null) {
				lf = new File[0];
			}
			for (File f : lf) {
				if (f.getName().endsWith(ext)) {
					files.add(f);
				}
			}
		}
		return files;
	}
    
    public BinaryMapIndexReader getReader(File f) throws IOException {
		RandomAccessFile mf = new RandomAccessFile(f.getPath(), "r");
		
		BinaryMapIndexReader reader = null;
		
			long val = System.currentTimeMillis();
			

			 
			reader = new BinaryMapIndexReader(mf);
			//addToCache(reader, f);
			
		
		return reader;
	}
    
    public void initializeNewResource(File file, BinaryMapIndexReader reader) {
		if (files.containsKey(file.getAbsolutePath())) {
			closeConnection(files.get(file.getAbsolutePath()), file.getAbsolutePath());
		
		}
		
		files.put(file.getAbsolutePath(), reader);
//		NativeOsmandLibrary nativeLib = NativeOsmandLibrary.getLoadedLibrary();
//		if (nativeLib != null) {
//			if (!nativeLib.initMapFile(file.getAbsolutePath())) {
//				log.error("Initializing native db " + file.getAbsolutePath() + " failed!"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
//			} else {
//				nativeFiles.add(file.getAbsolutePath());
//			}
//		}
	}
    
    protected void closeConnection(BinaryMapIndexReader c, String file) {
		files.remove(file);
//		if(nativeFiles.contains(file)){
//			NativeOsmandLibrary lib = NativeOsmandLibrary.getLoadedLibrary();
//			if(lib != null) {
//				lib.closeMapFile(file);
//				nativeFiles.remove(file);
//			}
//		}
		try {
			c.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onDestroy() {
    	// TODO Auto-generated method stub
    	alive=false;
    	super.onDestroy();
    	
    }
}
