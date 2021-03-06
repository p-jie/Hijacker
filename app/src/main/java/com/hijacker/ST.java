package com.hijacker;

/*
    Copyright (C) 2016  Christos Kyriakopoylos

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

import android.app.FragmentManager;

import java.util.ArrayList;
import java.util.List;

import static com.hijacker.MainActivity.PROCESS_AIREPLAY;
import static com.hijacker.MainActivity.SORT_BEACONS_FRAMES;
import static com.hijacker.MainActivity.SORT_DATA_FRAMES;
import static com.hijacker.MainActivity.SORT_NOSORT;
import static com.hijacker.MainActivity.SORT_PWR;
import static com.hijacker.MainActivity.aliases;
import static com.hijacker.MainActivity.completed;
import static com.hijacker.MainActivity.getFixed;
import static com.hijacker.MainActivity.getManuf;
import static com.hijacker.MainActivity.runInHandler;
import static com.hijacker.MainActivity.sort;
import static com.hijacker.MainActivity.startAireplay;
import static com.hijacker.MainActivity.stop;
import static com.hijacker.MainActivity.toSort;

class ST {
    static List<ST> STs = new ArrayList<>();
    static List<ST> marked = new ArrayList<>();
    static String paired, not_connected;
    static int connected=0;
    int pwr, id;
    private int frames, lost, total_frames=0, total_lost=0;
    long lastseen = 0;
    boolean isMarked = false;
    Tile tile;
    AP connectedTo = null;
    String mac, bssid, manuf, probes, alias;
    ST(String mac, String bssid, int pwr, int lost, int frames, String probes){
        this.mac = mac;
        this.id = STs.size();
        this.manuf = getManuf(this.mac);
        this.alias = aliases.get(this.mac);
        this.update(bssid, pwr, lost, frames, probes);
        STs.add(this);
        if(sort!=SORT_NOSORT) toSort = true;
    }
    void disconnect(){
        if(Airodump.getChannel() != connectedTo.ch){
            //switch channel only if airodump is running elsewhere
            stop(PROCESS_AIREPLAY);
            Airodump.startClean(connectedTo.ch);
        }
        startAireplay(this.bssid, this.mac);
    }
    void update(){
        //For refresh
        this.update(this.bssid, this.pwr, this.lost, this.frames, this.probes);
    }
    void update(String bssid, int pwr, int lost, int frames, String probes){
        if(connectedTo!=null){
            if(!connectedTo.mac.equals(bssid)){
                //Connected to a different network
                connectedTo.removeClient(this);
                connectedTo = AP.getAPByMac(bssid);
                if(connectedTo==null){
                    //Now not connected
                    connected--;
                    runInHandler(new Runnable(){
                        @Override
                        public void run(){
                            Tile.onCountsChanged();
                        }
                    });
                }else{
                    connectedTo.addClient(this);
                }
            }
        }else if(bssid!=null){
            //Now connected somewhere
            connectedTo = AP.getAPByMac(bssid);
            if(connectedTo!=null){
                //Now connected to known AP
                connected++;
                connectedTo.addClient(this);
                runInHandler(new Runnable(){
                    @Override
                    public void run(){
                        Tile.onCountsChanged();
                    }
                });
            }
        }
        if(frames!=this.frames || lost!=this.lost || this.lastseen==0){
            this.lastseen = System.currentTimeMillis();
        }
        if(!toSort && sort!=SORT_NOSORT){
            switch(sort){
                case SORT_BEACONS_FRAMES:
                    toSort = this.frames!=frames;
                    break;
                case SORT_DATA_FRAMES:
                    toSort = this.frames!=frames;
                    break;
                case SORT_PWR:
                    toSort = this.pwr!=pwr;
                    break;
            }
        }

        if(frames<this.frames || lost<this.lost){
            saveData();
        }else{
            this.lost = lost;
            this.frames = frames;
        }

        this.bssid = bssid;
        this.pwr = pwr;
        this.probes = probes.equals("") ? "No probes" : probes.replace(",", ", ");

        final String a, b, c;
        a = this.mac + (this.alias==null ? "" : " (" + alias + ')');
        if(connectedTo!=null){
            b = paired + connectedTo.mac + " (" + connectedTo.essid + ")";
        }else b = not_connected;
        c = "PWR: " + this.pwr + " | Frames: " + this.getFrames();
        runInHandler(new Runnable(){
            @Override
            public void run(){
                if(tile!=null) tile.update(a, b, c, ST.this.manuf);
                else tile = new Tile(AP.APs.size() + id, a, b, c, ST.this.manuf, ST.this);
                if(tile.st==null) tile = null;
                completed = true;
            }
        });
    }
    void showInfo(FragmentManager fragmentManager){
        STDialog dialog = new STDialog();
        dialog.info_st = this;
        dialog.show(fragmentManager, "STDialog");
    }
    void mark(){
        if(!marked.contains(this)){
            marked.add(this);
        }
        this.isMarked = true;
        Tile.filter();
    }
    void unmark(){
        if(marked.contains(this)){
            marked.remove(this);
        }
        this.isMarked = false;
        Tile.filter();
    }
    public String toString(){
        return this.mac + (this.alias==null ? "" : " (" + this.alias + ')') + ((this.bssid==null) ? "" : " (" + AP.getAPByMac(this.bssid).essid + ')');
    }
    public String getExported(){
        //MAC                BSSID               PWR  Frames    Lost  Manufacturer - Probes
        //00:11:22:33:44:55  00:11:22:33:44:55  -100  123456  123456  ExampleManufacturer - Probe1, Probe2, Probe3...
        String str = mac;
        str += getFixed(bssid==null ? "(not associated) " : bssid, 19);
        str += getFixed(Integer.toString(pwr), 6);
        str += getFixed(Integer.toString(getFrames()), 8);
        str += getFixed(Integer.toString(getLost()), 8);
        str += "  " + manuf  + (alias==null ? "" : " (" + alias + ')') + " - " + probes;

        return str;
    }
    public int getFrames(){ return total_frames + frames; }
    public int getLost(){ return total_lost + lost; }
    public void saveData(){
        total_frames += frames;
        total_lost += lost;
        frames = 0;
        lost = 0;
    }
    public static void saveAll(){
        for(int i=0;i<ST.STs.size();i++){
            ST.STs.get(i).saveData();
        }
    }
    static ST getSTByMac(String mac){
        for(int i=STs.size()-1;i>=0;i--){
            if(mac.equals(STs.get(i).mac)){
                return STs.get(i);
            }
        }
        return null;
    }
    static void clear(){
        STs.clear();
        marked.clear();
        connected = 0;
    }
}
