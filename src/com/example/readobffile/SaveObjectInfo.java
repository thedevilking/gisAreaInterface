package com.example.readobffile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;

import android.util.Log;

/** 
 * @author wwtao thedevilking@qq.com: 
 * @version ����ʱ�䣺2015-8-25 ����8:05:41 
 * ˵�� 
 */
public class SaveObjectInfo extends Thread
{
	public static class SingleInstance
	{
		static SaveObjectInfo single=new SaveObjectInfo();
	}
	
	//�̰߳�ȫ����ģʽ
	public static SaveObjectInfo getInstance()
	{
		return SingleInstance.single;
	}
	
	Queue<String> strToWrite;
	File file;
	FileOutputStream fout;
	
	private SaveObjectInfo()
	{
		strToWrite=new LinkedBlockingDeque<String>();
		file=new File("/sdcard/MapObjectTagValue.txt");
		if(!file.exists())
		{
			if(!file.getParentFile().exists())
				file.mkdirs();
			
			try {
				file.createNewFile();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		try {
			fout=new FileOutputStream(file, false);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		//�����첽��д�߳�
		this.start();
	}
	
	@Override
	public void run() {
		// TODO Auto-generated method stub
//		super.run();
		
		while(true)
		{
			if(!MainActivity.alive)
				break;
			
			if(!strToWrite.isEmpty())
			{
				String str=strToWrite.poll();
				str+="\n";
			
				try {
					fout.write(str.getBytes());
					fout.flush();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
//			Log.i("t","д��־�߳�");
		}
		
		try {
			fout.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void writeText(String str)
	{
		Log.i("test", str);
		
		strToWrite.add(str);
	}
}
