package com.example.readobffile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 
 * @author wwtao thedevilking@qq.com: 
 * @version 创建时间�?015-8-27 上午10:10:35 
 * 说明 
 */
public class PointInPolygon {
	
	/*public static void main(String[] arg)
	{
		
		//测试点是否在多边形内
		List<Double> x=new ArrayList<Double>();
		List<Double> y=new ArrayList<Double>();
		
		x.add(2d);
		x.add(1d);
		x.add(1d);
		x.add(2d);
		x.add(2d);
		
		x.add(1d);
		x.add(2d);
		x.add(3d);
		x.add(3d);
		x.add(4d);
		
		x.add(4d);
		x.add(3d);
		x.add(3d);
		x.add(5d);
		x.add(5d);
		
		y.add(1d);
		y.add(2d);
		y.add(3d);
		y.add(2d);
		y.add(3d);
		
		y.add(4d);
		y.add(4d);
		y.add(3d);
		y.add(2d);
		y.add(2d);
		
		y.add(3d);
		y.add(4d);
		y.add(5d);
		y.add(5d);
		y.add(1d);
		
		try {
			System.out.println(pointInPolygon1(1d,1d,x,y));
			System.out.println(pointInPolygon1(0d,0d,x,y));
			System.out.println(pointInPolygon1(2d,1d,x,y));
			System.out.println(pointInPolygon1(3d,1d,x,y));
			System.out.println(pointInPolygon1(4d,4d,x,y));
			System.out.println(pointInPolygon1(2d,5d,x,y));
			
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}*/

	static boolean pointInPolygon1(double x,double y,List<Double> polygonX,List<Double> polygonY) throws Exception
	{
		boolean in=false;
		
		//对多边形周边点的数组的合法�?判断
		if(polygonX==null || polygonY==null || 
				polygonX.size()!=polygonY.size() || polygonX.isEmpty())
		{
			throw (new Exception("多边形的点数组不合法"));
		}
		
		int crossCount=0;
		for(int i=0;i<polygonX.size();i++)
		{
			int p1=i;
			int p2=(i+1)%polygonX.size();
			
			//如果待测节点是多边形的顶点，认为在多边形�?
			if((x==polygonX.get(p1) && y==polygonY.get(p1))||(x==polygonX.get(p2) && y==polygonY.get(p2)))
			{
				in=true;
				break;
			}
			
			//特殊情况
			//多边形相邻两节点的y坐标�?��
			if(polygonY.get(p1)==polygonY.get(p2))
			{
				continue;
			}
			
			//待测节点不在两节点的y轴之�?
			if(y<polygonY.get(p1) && y<polygonY.get(p2))
				continue;
			if(y>polygonY.get(p2) && y>polygonY.get(p2))
				continue;
			
			//求交点的x坐标�?
			double result=(double)(y-polygonY.get(p1))*(double)(polygonX.get(p2)-polygonX.get(p1))
					/((double)(polygonY.get(p2)-polygonY.get(p1))) + polygonX.get(p1);
			
			if(result>x)
				++crossCount;
		}
		if(crossCount%2==1)
			in=true;
		
		return in;
	}
	
	public static boolean pointInPolygon(double x,double y,List<Double> polygonX,List<Double> polygonY) throws Exception
	{
		boolean in=false;
		
		//对多边形周边点的数组的合法�?判断
		if(polygonX==null || polygonY==null || 
				polygonX.size()!=polygonY.size() || polygonX.isEmpty())
		{
			throw (new Exception("多边形的点数组不合法"));
		}
		
		
		double minx=Double.MAX_VALUE;
		double maxx=Double.MIN_VALUE;
		double miny=Double.MAX_VALUE;
		double maxy=Double.MIN_VALUE;
		
		Iterator<Double> iter=polygonX.iterator();
		while(iter.hasNext())
		{
			double temp=iter.next();
			if(temp>maxx)
				maxx=temp;
			
			if(temp<minx)
				minx=temp;
		}
		
		iter=polygonY.iterator();
		while(iter.hasNext())
		{
			double temp=iter.next();
			if(temp>maxy)
				maxy=temp;
			
			if(temp<miny)
				miny=temp;
		}
		
		if(x<minx || x>maxx || y<miny || y>maxy)
		{
			return false;
		}
		
		
		int i,j;
		for(i=0,j=polygonX.size()-1;i<polygonX.size();j=i++)
		{
			if(( (polygonY.get(i)>y) != (polygonY.get(j)>y)) 
					&& ( x< (polygonX.get(j)-polygonX.get(i)) * (y-polygonY.get(i)) / (polygonY.get(j)-polygonY.get(i)) + polygonX.get(i)) )
			{
				in=!in;
			}
		}
		
		
		return in;
	}
}
