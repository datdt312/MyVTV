package com.example.myplayer;

import android.util.Log;

import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created By Ele
 * on 2020/6/4
 **/
public class PacketQueue {

    private LinkedBlockingQueue<PacketBean> list = new LinkedBlockingQueue();

    public boolean enQueue(PacketBean packetBean){
        if (list != null){
            return list.offer(packetBean);
        }
        return false;
    }

    public PacketBean deQueue(){
        if (list == null){
            return null;
        }
        return list.poll();
    }

    public PacketBean getFirst(){
        if (list == null){
            return null;
        }
        return list.peek();
    }

    public int getQueueSize(){
        if (list == null){
            return 0;
        }
        return list.size();
    }

    public synchronized void clear(){

        if (list == null || list.isEmpty()){
            return;
        }
        list.clear();
    }

    public double getMaxPts(){
        if (list == null || list.isEmpty()){
            return 0;
        }
        double max = -1;
        PacketBean bean;
        Iterator<PacketBean> iterator = list.iterator();
        while (iterator.hasNext()){
            bean = iterator.next();
            max = bean.getPts() > max ? bean.getPts() : max;
        }
        Log.e("kzg","******************queue max pts:"+max);
        return max;
    }

}
