/**
 Copyright 2015 Miyuru Dayarathna

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.linear.storm.input;

import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * @author miyuru
 *
 */
public class InputEventInjectorSpout extends BaseRichSpout {
	SpoutOutputCollector _collector;
	String carDataFile;
	long cnt = 0, nhist_cnt = 0;
	boolean completedLoadingHistory = false; //This flag indicates whether we have completed loading history data or not.
	
	public InputEventInjectorSpout(){
        Properties properties = new Properties();
        InputStream propertiesIS = InputEventInjectorSpout.class.getClassLoader().getResourceAsStream(org.linear.storm.util.Constants.CONFIG_FILENAME);
        if (propertiesIS == null)
        {
            throw new RuntimeException("Properties file '" + org.linear.storm.util.Constants.CONFIG_FILENAME + "' not found in classpath");
        }
        try{
        	properties.load(propertiesIS);
        }catch(IOException ec){
        	ec.printStackTrace();
        }
		carDataFile = properties.getProperty(org.linear.storm.util.Constants.LINEAR_CAR_DATA_POINTS);
	}
	
	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer){
		//Here we declare the set of streams that are emitted by this Spout.
		
		//_collector.emit("position_report", new Values(time, vid, spd, xway, lane, dir, mile));
		
		//Position report Stream (Type=0, Time, VID, Spd, Xway, Lane, Dir, Seg, Pos)	
		declarer.declareStream("position_report", new Fields(
                "secfromstart",
                "vid",
                "speed",
                "xway",
                "lane",
                "dir",
                "mile",
                "ofst"));
		//Account Balance Report Stream (Type=2, Time, VID, QID)
		declarer.declareStream("accbal_report", new Fields(
                "secfromstart",
                "vid",
                "qid"));
		//Expenditure report (Type=3, Time, VID, XWay, QID, Day)
		declarer.declareStream("daily_exp", new Fields(
                "secfromstart",
                "vid",
                "xway",
                "qid",
                "day"));
	}
	
	@Override
	public void nextTuple(){
		if(completedLoadingHistory){
			try{
				BufferedReader in = new BufferedReader(new FileReader(carDataFile));
				
				String line;
				
				/*
				 
				 The input file has the following format
				 
				    Cardata points input tuple format
					0 - type of the packet (0=Position Report, 1=Account Balance, 2=Expenditure, 3=Travel Time [According to Richard's thesis. See Below...])
					1 - Seconds since start of simulation (i.e., time is measured in seconds)
					2 - Car ID (0..999,999)
					3 - An integer number of miles per hour (i.e., speed) (0..100)
					4 - Expressway number (0..9)
					5 - The lane number (There are 8 lanes altogether)(0=Ramp, 1=Left, 2=Middle, 3=Right)(4=Left, 5=Middle, 6=Right, 7=Ramp)
					6 - Direction (west = 0; East = 1)
					7 - Mile (This corresponds to the seg field in the original table) (0..99)
					8 - Distance from the last mile post (0..1759) (Arasu et al. Pos(0...527999) identifies the horizontal position of the vehicle as a meaure of number of feet
					    from the western most position on the expressway)
					9 - Query ID (0..999,999)
					10 - Starting milepost (m_init) (0..99)
					11 - Ending milepost (m_end) (0..99)
					12 - day of the week (dow) (0=Sunday, 1=Monday,...,6=Saturday) (in Arasu et al. 1...7. Probably this is wrong because 0 is available as DOW)
					13 - time of the day (tod) (0:00..23:59) (in Arasu et al. 0...1440)
					14 - day
					
					Notes
					* While Richard thesis explain the input tuple formats, it is probably not correct.
					* The correct type number would be 
					* 0=Position Report (Type=0, Time, VID, Spd, Xway, Lane, Dir, Seg, Pos)
					* 2=Account Balance Queries (because all and only all the 4 fields available on tuples with type 2) (Type=2, Time, VID, QID)
					* 3=Daily expenditure (Type=3, Time, VID, XWay, QID, Day). Here if day=1 it is yesterday. d=69 is 10 weeks ago. 
					* 4=Travel Time requests (Types=4, Time, VID, XWay, QID, Sinit, Send, DOW, TOD)
					
					history data input tuple format
					0 - Car ID
					1 - day
					2 - x - Expressway number
					3 - daily expenditure
					
					E.g.
					#(1 3 0 31)
					#(1 4 0 61)
					#(1 5 0 34)
					#(1 6 0 30)
					#(1 7 0 63)
					#(1 8 0 55)
					
					//Note that since the historical data table's key is made out of several fields it was decided to use a relation table 
					//instead of using a hashtable
				 
				 */
				
				
				while((line = in.readLine()) != null){	
//					
//					System.out.println("Called : " + cnt);
//					cnt++;
					
					Long time = -1l;
					Integer vid = -1;
					Integer qid = -1;
					Byte spd = -1;
					Byte xway = -1;
					Byte mile = -1;
					Short ofst = -1;
					Byte lane = -1;
					Byte dir = -1;
					Integer day = -1;
					
					line = line.substring(2, line.length() - 1);
					
					String[] fields = line.split(" ");
					byte typeField = Byte.parseByte(fields[0]); 
					
					//In the case of Storm it seems that we need not to send the type of the tuple through the network. It is because 
					//the event itself has some associated type. This seems to be an advantage of Storm compared to other message passing based solutions.
					switch(typeField){
						case 0:
							//Need to calculate the offset value
							String offset = "" + ((short)(Integer.parseInt(fields[8]) - (Integer.parseInt(fields[7]) * 5280)));
							//This is a position report (Type=0, Time, VID, Spd, Xway, Lane, Dir, Seg, Pos)
							time = Long.parseLong(fields[1]);
							vid = Integer.parseInt(fields[2]);
							spd = Byte.parseByte(fields[3]);
							xway = Byte.parseByte(fields[4]);
							lane = Byte.parseByte(fields[5]);
							dir = Byte.parseByte(fields[6]);
							mile = Byte.parseByte(fields[7]);
							ofst = Short.parseShort(offset);
							
							_collector.emit("position_report", new Values(time, vid, spd, xway, lane, dir, mile, ofst));
							break;
						case 2:
							time = Long.parseLong(fields[1]);
							vid = Integer.parseInt(fields[2]);
							qid = Integer.parseInt(fields[9]);
							//This is an Account Balance report (Type=2, Time, VID, QID)
							_collector.emit("accbal_report", new Values(time, vid, qid));
							break;
						case 3 : 
							time = Long.parseLong(fields[1]);
							vid = Integer.parseInt(fields[2]);
							xway = Byte.parseByte(fields[4]);
							qid = Integer.parseInt(fields[9]);
							day = Integer.parseInt(fields[14]);
							//This is an Expenditure report (Type=3, Time, VID, XWay, QID, Day)
							_collector.emit("daily_exp", new Values(time, vid, xway, qid, day));
							break;
						case 4:
							//This is a travel time report (Types=4, Time, VID, XWay, QID, Sinit, Send, DOW, TOD)
							break;
						case 5:
							System.out.println("Travel time query was issued : " + line);
							break;
					}
				}
				System.out.println("Done emitting the input tuples...");
			}catch(IOException ec){
				ec.printStackTrace();
			}
		}else{
			completedLoadingHistory = HistoryLoadingNotifierClient.isHistoryLoaded();
//			try{
//				Thread.sleep(100000);//just wait one second and check again
//			}catch(InterruptedException e){
//				//Just ignore
//			}
			
			
			if(!completedLoadingHistory){
	        	System.out.print(".");
	        }else{
	        	System.out.println("\r\nhistory loaded.");
	        }
			
			try{
				Thread.sleep(1000);//just wait one second and check again
			}catch(InterruptedException e){
				//Just ignore
			}
		}
	}
	
	@Override
	public void open(Map conf, TopologyContext context, SpoutOutputCollector collector){
		_collector = collector;		
	}
}
