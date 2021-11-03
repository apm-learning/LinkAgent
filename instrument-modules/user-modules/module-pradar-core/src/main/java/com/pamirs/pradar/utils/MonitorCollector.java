/**
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pamirs.pradar.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.pamirs.pradar.AppNameUtils;
import com.pamirs.pradar.Pradar;
import com.pamirs.pradar.PradarCoreUtils;
import com.pamirs.pradar.PradarSwitcher;
import com.shulie.instrument.simulator.api.executors.ExecutorServiceFactory;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.util.Util;

/**
 * @author angju
 * @date 2020/7/20 13:52
 * 服务器指标信息收集
 */
public class MonitorCollector {

    public static final String CPU_USAGE_KEY = "cpuUsage";
    public static final String IO_WAIT_KEY = "ioWait";
    public static final String NETWORK_BANDWIDTH_KEY = "networkBandwidth";
    public static final String NETWORK_BANDWIDTH_RATE_KEY = "networkBandwidthRate";
    private final static Logger logger = LoggerFactory.getLogger(MonitorCollector.class.getName());
    private static final ThreadLocal<DecimalFormat> decimalFormatThreadLocal = new ThreadLocal<DecimalFormat>() {
        @Override
        protected DecimalFormat initialValue() {
            return new DecimalFormat("0.00");
        }
    };
    private static final String NETWORK_USAGE_KEY = "networkUsage";
    private static final String NETWORK_SPEED_KEY = "networkSpeed";
    private static long lastErrorTime;
    private static MonitorCollector INSTANCE;
    public SystemInfo si;
    private int printLogCount = 2;
    private ScheduledFuture future;

    private MonitorCollector() {
        si = new SystemInfo();
    }

    public static MonitorCollector getInstance() {
        if (INSTANCE == null) {
            synchronized (MonitorCollector.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MonitorCollector();
                }
            }
        }
        return INSTANCE;
    }

    public HardwareAbstractionLayer getHardware() {
        return si.getHardware();
    }

    /**
     * 初始化 monitor 数据收集任务
     */
    public void start() {
        boolean runningInContainer = isRunningInsideDocker();
        if (runningInContainer) {
            executeResourcesInfoCollectingTaskInsideContainer();
        } else {
            executeResourcesInfoCollectingTaskOutsideContainer();
        }
    }

    private void executeResourcesInfoCollectingTaskInsideContainer() {
        future = ExecutorServiceFactory.getFactory().scheduleAtFixedRate(new Runnable() {

            private ContainerStatsInfoCollector collector;

            @Override
            public void run() {

                if (!PradarSwitcher.isMonitorEnabled()) {
                    return;
                }
                if (collector == null) {
                    collector = new ContainerStatsInfoCollector();
                }
                try {
                    long timeStamp = System.currentTimeMillis() / 1000;
                    String appName = AppNameUtils.appName();
                    StringBuilder stringBuilder = new StringBuilder();
                    ContainerStatsInfoCollector.ContainerStatsInfo statsInfo = collector.getContainerStatsInfo();

                    stringBuilder.append(appName).append("|")
                        .append(timeStamp).append("|")
                        .append(StringUtils.isBlank(Pradar.PRADAR_TENANT_KEY) ? "" : Pradar.PRADAR_TENANT_KEY).append(
                            "|")
                        .append(StringUtils.isBlank(Pradar.PRADAR_ENV_CODE) ? "" : Pradar.PRADAR_ENV_CODE).append("|")
                        .append(StringUtils.isBlank(Pradar.PRADAR_USER_ID) ? "" : Pradar.PRADAR_USER_ID).append("|")
                        .append(Pradar.AGENT_ID_NOT_CONTAIN_USER_INFO).append("|")
                        .append(statsInfo.getCpuUsagePercent()).append("|")
                        .append(statsInfo.getLatest1MinLoadAvg()).append("|")
                        .append(statsInfo.getLatest5MinLoadAvg()).append("|")
                        .append(statsInfo.getLatest15MinLoadAvg()).append("|")
                        .append(statsInfo.getMemoryUsagePercent()).append("|")
                        .append(statsInfo.getTotalMemory()).append("|")
                        .append(statsInfo.getAvailableMemory()).append("|")
                        .append(statsInfo.getCpuIoWaitUsagePercent()).append("|")
                        .append(statsInfo.getNetworkUsage()).append('|')
                        .append(statsInfo.getNetworkSpeed()).append('|')
                        .append(statsInfo.getCoresNum()).append("|")
                        .append(statsInfo.getTotalDiskSpace()).append('|')
                        .append(statsInfo.getUsableDiskSpace()).append('|')
                        .append(statsInfo.getDiskReadBytes()).append('|')
                        .append(statsInfo.getDiskWriteBytes()).append('|')
                        .append(1).append('|')
                        .append(Pradar.PRADAR_MONITOR_LOG_VERSION)
                        .append(PradarCoreUtils.NEWLINE);
                    Pradar.commitMonitorLog(stringBuilder.toString());
                } catch (Throwable e) {
                    if (printLogCount > 0) {
                        printLogCount--;
                        logger.error("write server monitor error!", e);
                    }
                }
            }
        }, 5, 1, TimeUnit.SECONDS);
    }

    private void executeResourcesInfoCollectingTaskOutsideContainer() {
        future = ExecutorServiceFactory.getFactory().scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!PradarSwitcher.isMonitorEnabled()) {
                    return;
                }
                try {
                    long timeStamp = System.currentTimeMillis() / 1000;
                    String appName = AppNameUtils.appName();
                    StringBuilder stringBuilder = new StringBuilder();
                    HardwareAbstractionLayer hal = si.getHardware();
                    CentralProcessor processor = si.getHardware().getProcessor();
                    Map<String, String> cpuInfoResult = getCpuUsageAndIoWaitAndNetwork(hal);
                    String[] cpuLoad = getCpuLoad(processor);
                    String memoryUsage = getMemoryUsage(hal.getMemory());
                    int cpuNum = getCpus(processor);
                    long[] diskReadWrites = getDisk(hal);
                    hal.getNetworkIFs()[0].getSpeed();
                    long[] diskUses = getFileSystem();
                    stringBuilder.append(appName).append("|")
                        .append(timeStamp).append("|")
                        .append(StringUtils.isBlank(Pradar.PRADAR_TENANT_KEY) ? "" : Pradar.PRADAR_TENANT_KEY).append(
                            "|")
                        .append(StringUtils.isBlank(Pradar.PRADAR_ENV_CODE) ? "" : Pradar.PRADAR_ENV_CODE).append("|")
                        .append(StringUtils.isBlank(Pradar.PRADAR_USER_ID) ? "" : Pradar.PRADAR_USER_ID).append("|")
                        .append(Pradar.AGENT_ID_NOT_CONTAIN_USER_INFO).append("|")
                        .append(cpuInfoResult.get(CPU_USAGE_KEY) == null ? "" : cpuInfoResult.get(CPU_USAGE_KEY))
                        .append("|")
                        .append(cpuLoad[0]).append("|")
                        .append(cpuLoad[1]).append("|")
                        .append(cpuLoad[2]).append("|")
                        .append(memoryUsage).append("|")
                        .append(hal.getMemory().getTotal()).append("|")
                        .append(hal.getMemory().getAvailable()).append("|")
                        //                            .append(cpuInfoResult.get(IO_WAIT_KEY) == null ? "" :
                        //                            cpuInfoResult.get(IO_WAIT_KEY)).append("|")
                        .append(0).append("|")
                        //                            .append(cpuInfoResult.get(NETWORK_BANDWIDTH_RATE_KEY) == null ?
                        //                            0 : cpuInfoResult.get(NETWORK_BANDWIDTH_RATE_KEY)).append('|')
                        .append(0).append("|")
                        .append(cpuInfoResult.get(NETWORK_BANDWIDTH_KEY) == null ? 0
                            : cpuInfoResult.get(NETWORK_BANDWIDTH_KEY)).append('|')
                        .append(cpuNum).append("|")
                        .append(diskUses != null ? diskUses[0] : "").append('|')
                        .append(diskUses != null ? diskUses[1] : "").append('|')
                        .append(diskReadWrites != null ? diskReadWrites[0] : "").append('|')
                        .append(diskReadWrites != null ? diskReadWrites[1] : "").append('|')
                        .append(0).append('|')
                        .append(Pradar.PRADAR_MONITOR_LOG_VERSION)
                        .append(PradarCoreUtils.NEWLINE);
                    Pradar.commitMonitorLog(stringBuilder.toString());
                } catch (Throwable e) {
                    if (printLogCount > 0) {
                        printLogCount--;
                        logger.error("write server monitor error!", e);
                    }
                }
            }
        }, 5, 1, TimeUnit.SECONDS);
    }

    public long[] getFileSystem() {
        try {
            FileSystem fileSystem = si.getOperatingSystem().getFileSystem();
            List<OSFileStore> fileStores = Arrays.asList(fileSystem.getFileStores());
            long totalSpace = 0L;
            long useSpace = 0L;
            for (OSFileStore store : fileStores) {
                totalSpace += store.getTotalSpace();
                useSpace += store.getUsableSpace();
            }
            return new long[] {totalSpace, useSpace};
        } catch (Throwable e) {
            if (System.currentTimeMillis() - lastErrorTime > 600000) {
                logger.warn("getFileSystem error! ", e);
                lastErrorTime = System.currentTimeMillis();
            }
            return null;
        }

    }

    /**
     * 获取磁盘
     *
     * @return
     */
    public long[] getDisk(HardwareAbstractionLayer hal) {
        try {
            List<HWDiskStore> diskStores = Arrays.asList(hal.getDiskStores());
            long readBytes = 0L;
            long writeBytes = 0L;
            for (HWDiskStore hwDiskStore : diskStores) {
                readBytes += hwDiskStore.getReadBytes();
                writeBytes += hwDiskStore.getWriteBytes();
            }
            return new long[] {readBytes, writeBytes};
        } catch (Throwable e) {
            return null;
        }

    }

    /**
     * cpu 核数量
     */
    public int getCpus(CentralProcessor processor) {
        return processor.getPhysicalProcessorCount();
    }

    /**
     * 网络带宽以及带宽使用率
     */
    private Map<String, Object> getNetwork(HardwareAbstractionLayer hal, String serverIp) {
        List<NetworkIF> networkIFsBefore = Arrays.asList(hal.getNetworkIFs());
        long beforeBytesRecv = 0;
        long beforeBytesSend = 0;
        String name = null;
        Map<String, Object> result = new HashMap<String, Object>();
        for (NetworkIF networkIF : networkIFsBefore) {
            boolean isMatched = false;
            for (String ip : networkIF.getIPv4addr()) {
                if (StringUtils.equals(ip, serverIp)) {
                    isMatched = true;
                    break;
                }
            }
            if (isMatched) {
                name = networkIF.getDisplayName();
                beforeBytesRecv = networkIF.getBytesRecv();
                beforeBytesSend = networkIF.getBytesSent();
                break;
            }
        }
        Util.sleep(450);
        List<NetworkIF> networkIFsAfter = Arrays.asList(hal.getNetworkIFs());
        long afterBytesRecv = 0;
        long afterBytesSend = 0;
        for (NetworkIF networkIF : networkIFsAfter) {
            if (networkIF.getIPv4addr().length > 0 && networkIF.getIPv4addr()[0].equals(serverIp)) {
                afterBytesRecv = networkIF.getBytesRecv();
                afterBytesSend = networkIF.getBytesSent();
                break;
            }
        }

        if (name != null) {
            long networkSpeed = getSpeed(name);
            if (networkSpeed > 0) {
                Double networkUsage = ((afterBytesRecv - beforeBytesRecv) + (afterBytesSend - beforeBytesSend)) * 100d
                    / networkSpeed;
                result.put(NETWORK_USAGE_KEY, decimalFormatThreadLocal.get().format(networkUsage));
                result.put(NETWORK_SPEED_KEY, networkSpeed);
            } else {
                result.put(NETWORK_USAGE_KEY, 0);
                result.put(NETWORK_SPEED_KEY, 0);
            }
        }
        return result;
    }

    /**
     * 获取内存使用率
     *
     * @param memory
     * @return
     */
    public String getMemoryUsage(GlobalMemory memory) {
        return decimalFormatThreadLocal.get().format(
            (memory.getTotal() - memory.getAvailable()) * 100d / memory.getTotal());
    }

    /**
     * 获取总内存
     *
     * @param memory
     * @return
     */
    private long getMemory(GlobalMemory memory) {
        return memory.getTotal();
    }

    /**
     * 获取cpuLoad
     *
     * @return
     */
    public String[] getCpuLoad(CentralProcessor processor) {
        double[] loadAverage = processor.getSystemLoadAverage(3);
        return new String[] {decimalFormatThreadLocal.get().format(loadAverage[0]),
            decimalFormatThreadLocal.get().format(loadAverage[1]), decimalFormatThreadLocal.get().format(
            loadAverage[2])};
    }

    /**
     * 获取cpu利用率和iowait
     *
     * @return
     */
    public Map<String, String> getCpuUsageAndIoWaitAndNetwork(HardwareAbstractionLayer hal) {
        long[] prevTicks = hal.getProcessor().getSystemCpuLoadTicks();
        NetworkIF eth = null;
        for (NetworkIF networkIF : hal.getNetworkIFs()) {
            if ("eth0".equals(networkIF.getDisplayName())) {
                eth = networkIF;
                break;
            }
        }
        long speed = eth.getSpeed();
        long bytesRecv = eth.getBytesRecv();
        long bytesSent = eth.getBytesSent();
        long sleep = 450;
        Util.sleep(sleep);
        long[] ticks = hal.getProcessor().getSystemCpuLoadTicks();

        long user = ticks[CentralProcessor.TickType.USER.getIndex()]
            - prevTicks[CentralProcessor.TickType.USER.getIndex()];
        long nice = ticks[CentralProcessor.TickType.NICE.getIndex()]
            - prevTicks[CentralProcessor.TickType.NICE.getIndex()];
        long sys = ticks[CentralProcessor.TickType.SYSTEM.getIndex()]
            - prevTicks[CentralProcessor.TickType.SYSTEM.getIndex()];
        long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()]
            - prevTicks[CentralProcessor.TickType.IDLE.getIndex()];
        long iowait = ticks[CentralProcessor.TickType.IOWAIT.getIndex()]
            - prevTicks[CentralProcessor.TickType.IOWAIT.getIndex()];
        long irq = ticks[CentralProcessor.TickType.IRQ.getIndex()]
            - prevTicks[CentralProcessor.TickType.IRQ.getIndex()];
        long softirq = ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()]
            - prevTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()];
        //        long steal = ticks[CentralProcessor.TickType.STEAL.getIndex()] - prevTicks[CentralProcessor
        //        .TickType.STEAL.getIndex()];
        //        long totalCpu = user + nice + sys + idle + iowait + irq + softirq + steal;
        long totalCpu = user + nice + sys + idle + iowait + irq + softirq;
        double cpuUsage = (totalCpu - idle) * 100d / totalCpu;
        double ioWait = iowait * 100d / totalCpu;
        Map<String, String> result = new HashMap<String, String>();
        if (Double.isNaN(cpuUsage)) {
            result.put(CPU_USAGE_KEY, decimalFormatThreadLocal.get().format(0));
        } else {
            result.put(CPU_USAGE_KEY, decimalFormatThreadLocal.get().format(cpuUsage));
        }
        if (Double.isNaN(ioWait)) {
            result.put(IO_WAIT_KEY, decimalFormatThreadLocal.get().format(0));
        } else {
            result.put(IO_WAIT_KEY, decimalFormatThreadLocal.get().format(ioWait));
        }

        // 如果拿不到网卡,默认10000Mbs, 如果控制台配置了host网卡, 会矫正数据
        if (speed == 0) {
            speed = 10000 * 1024 * 1024;
        }
        result.put(NETWORK_BANDWIDTH_KEY, speed / 1024 / 1024 + "");
        long bytes = eth.getBytesRecv() - bytesRecv + eth.getBytesSent() - bytesSent;
        double networkUsage = Double.parseDouble(
            new DecimalFormat("0.0000000").format(100 * bytes / (speed / 8.0) / sleep * 1000));
        result.put(NETWORK_BANDWIDTH_RATE_KEY, networkUsage + "");

        return result;
    }

    public long getSpeed(String name) {
        Process pro1 = null;
        Runtime r = Runtime.getRuntime();
        String line = null;
        String speed = null;
        BufferedReader in1 = null;
        try {
            String command = "ethtool " + name;

            pro1 = r.exec(command);
            in1 = new BufferedReader(new InputStreamReader(pro1.getInputStream()));

            while ((line = in1.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("Speed:")) {
                    String[] temp = org.apache.commons.lang.StringUtils.split(line, ' ');
                    speed = temp[1];
                    break;
                }
            }

            if (speed != null) {
                if (speed.contains("Mb/s")) {
                    String speedTemp = speed.replace("Mb/s", "");
                    return Long.valueOf(speedTemp) * 1024 * 1024;
                } else if (speed.contains("Gb/s")) {
                    String speedTemp = speed.replace("Gb/s", "");
                    return Long.valueOf(speedTemp) * 1024 * 1024 * 1024;
                }
            }
        } catch (IOException e) {
            //            logger.error("getSpeed error {}",e.getMessage());
        } finally {
            if (pro1 != null) {
                pro1.destroy();
            }
            if (in1 != null) {
                try {
                    in1.close();
                } catch (IOException e) {
                    logger.error("getSpeed error {}", e);
                }
            }

        }

        return 0;
    }

    private boolean isRunningInsideDocker() {
        File file = new File("/proc/1/cgroup");
        if (!file.exists()) {
            return false;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("/docker")) {
                    return true;
                }
            }
        } catch (Exception e) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {

                }
            }
        }
        return false;
    }

    public void stop() {
        if (future != null && !future.isCancelled() && !future.isDone()) {
            future.cancel(true);
        }
    }
}
