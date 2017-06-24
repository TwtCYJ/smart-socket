package net.vinote.smart.socket.transport.nio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by zhengjunwei on 2017/6/21.
 */
public class SessionReadThread extends Thread {
    private static final Logger logger = LogManager.getLogger(SessionReadThread.class);
    private Set<SelectionKey> selectionKeySet = new HashSet<SelectionKey>();
    /**
     * 需要进行数据输出的Session集合
     */
    private ConcurrentLinkedQueue<SelectionKey> newSelectionKeySet1 = new ConcurrentLinkedQueue<SelectionKey>();
    /**
     * 需要进行数据输出的Session集合
     */
    private ConcurrentLinkedQueue<SelectionKey> newSelectionKeySet2 = new ConcurrentLinkedQueue<SelectionKey>();
    /**
     * 需要进行数据输出的Session集合存储控制标，true:newSelectionKeySet1,false:newSelectionKeySet2。由此减少锁竞争
     */
    private boolean switchFlag = false;

    private int waitTime = 1;

    private int connectNums = 0;

    public void notifySession(SelectionKey session) {
        session.interestOps(session.interestOps() & ~SelectionKey.OP_READ);
        if (switchFlag) {
            newSelectionKeySet1.add(session);
        } else {
            newSelectionKeySet2.add(session);
        }
        if (waitTime != 1) {
            synchronized (this) {
                this.notifyAll();
            }
        }

    }

    @Override
    public void run() {
        while (true) {
            if (selectionKeySet.isEmpty() && newSelectionKeySet1.isEmpty() && newSelectionKeySet2.isEmpty()) {
                synchronized (this) {
                    if (selectionKeySet.isEmpty() && newSelectionKeySet1.isEmpty() && newSelectionKeySet2.isEmpty()) {
                        try {
                            long start = System.currentTimeMillis();
                            this.wait(waitTime);
                            if (waitTime < 2000) {
                                waitTime += 100;
                            } else {
                                waitTime = 0;
                            }
                            if (logger.isTraceEnabled()) {
                                logger.trace("nofity sessionReadThread,waitTime:" + waitTime + " , real waitTime:" + (System.currentTimeMillis() - start));
                            }
                        } catch (InterruptedException e) {
                            logger.catching(e);
                        }
                    }
                }
            }

            if (switchFlag) {
//                synchronized (newSelectionKeySet2) {
                while(true){
                    SelectionKey key = newSelectionKeySet2.poll();
                    if(key==null){
                        break;
                    }
                    try {
                        SocketChannel socketChannel = (SocketChannel) key.channel();
                        NioAttachment attach = (NioAttachment) key.attachment();
                        NioSession<?> session = attach.getSession();
                        //未读到数据则关注读
                        int readSize = 0;
                        if ((readSize = socketChannel.read(session.flushReadBuffer())) == 0) {
                            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                            key.selector().wakeup();//一定要唤醒一次selector
                        } else if (readSize > 0) {
                            selectionKeySet.add(key);
                        }
                    } catch (Exception e) {
                        key.cancel();
                    }
                }

            } else {
                while(true){
                    SelectionKey key = newSelectionKeySet1.poll();
                    if(key==null){
                        break;
                    }
                    try {
                        SocketChannel socketChannel = (SocketChannel) key.channel();
                        NioAttachment attach = (NioAttachment) key.attachment();
                        NioSession<?> session = attach.getSession();
                        //未读到数据则关注读
                        int readSize = 0;
                        if ((readSize = socketChannel.read(session.flushReadBuffer())) == 0) {
                            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                            key.selector().wakeup();//一定要唤醒一次selector
                        } else if (readSize > 0) {
                            selectionKeySet.add(key);
                        }
                    } catch (Exception e) {
                        key.cancel();
                    }
                }
            }
            switchFlag = !switchFlag;
            connectNums = selectionKeySet.size();


            Iterator<SelectionKey> iterator = selectionKeySet.iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                try {
                    SocketChannel socketChannel = (SocketChannel) key.channel();
                    NioAttachment attach = (NioAttachment) key.attachment();
                    NioSession<?> session = attach.getSession();
                    //未读到数据则关注读
                    int readSize = 0;
                    if ((readSize = socketChannel.read(session.flushReadBuffer())) == 0) {
                        if (attach.tryRead++ > 10) {
                            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                            key.selector().wakeup();//一定要唤醒一次selector
                            iterator.remove();
                        }

                    } else if (readSize == -1) {
                        iterator.remove();
                    } else {
                        attach.tryRead = 0;
                    }

                } catch (Exception e) {
                    key.cancel();
                    iterator.remove();
                }
                waitTime = 1;
            }

        }
    }

    public int getConnectNums() {
        return connectNums;
    }
}