/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.yeelight.internal.lib.services;

import java.awt.Color;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.openhab.binding.yeelight.internal.lib.device.DeviceBase;
import org.openhab.binding.yeelight.internal.lib.device.DeviceFactory;
import org.openhab.binding.yeelight.internal.lib.device.DeviceStatus;
import org.openhab.binding.yeelight.internal.lib.enums.DeviceAction;
import org.openhab.binding.yeelight.internal.lib.listeners.DeviceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DeviceManager} is a class for managing all devices.
 *
 * @author Coaster Li - Initial contribution
 * @author Joe Ho - Added duration
 */
public class DeviceManager {
    private final Logger logger = LoggerFactory.getLogger(DeviceManager.class);

    private static final String TAG = DeviceManager.class.getSimpleName();

    private static final String MULTI_CAST_HOST = "239.255.255.250";
    private static final int MULTI_CAST_PORT = 1982;
    private static final String DISCOVERY_MSG = "M-SEARCH * HTTP/1.1\r\n" + "HOST:" + MULTI_CAST_HOST + ":"
            + MULTI_CAST_PORT + "\r\n" + "MAN:\"ssdp:discover\"\r\n" + "ST:wifi_bulb\r\n";

    private static final int TIMEOUT = 10000;

    public static DeviceManager sInstance;
    public volatile boolean mSearching = false;

    public Map<String, DeviceBase> mDeviceList = new HashMap<>();
    public List<DeviceListener> mListeners = new ArrayList<>();

    private ExecutorService executorService;

    private DeviceManager() {
    }

    public static DeviceManager getInstance() {
        if (sInstance == null) {
            sInstance = new DeviceManager();
        }
        return sInstance;
    }

    public void registerDeviceListener(DeviceListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void unregisterDeviceListener(DeviceListener listener) {
        mListeners.remove(listener);
    }

    public void startDiscovery() {
        startDiscovery(-1);
    }

    public void startDiscovery(long timeToStop) {
        searchDevice();
        if (timeToStop > 0) {
            new Thread(() -> {
                try {
                    Thread.sleep(timeToStop);
                } catch (InterruptedException e) {
                    logger.debug("Interrupted while sleeping", e);
                } finally {
                    stopDiscovery();
                }
            }).start();
        }
    }

    public void stopDiscovery() {
        mSearching = false;
    }

    private void searchDevice() {
        if (mSearching) {
            logger.debug("{}: Already in discovery, return!", TAG);
            return;
        }

        logger.debug("Starting Discovery");

        try {
            final InetAddress multicastAddress = InetAddress.getByName(MULTI_CAST_HOST);

            final List<NetworkInterface> networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                    .stream().filter(device -> {
                        try {
                            return device.isUp() && !device.isLoopback();
                        } catch (SocketException e) {
                            logger.debug("Failed to get device information", e);
                            return false;
                        }
                    }).collect(Collectors.toList());

            executorService = Executors.newFixedThreadPool(networkInterfaces.size());
            mSearching = true;

            for (final NetworkInterface networkInterface : networkInterfaces) {
                logger.debug("Starting Discovery on: {}", networkInterface.getDisplayName());

                executorService.execute(() -> {
                    try (MulticastSocket multiSocket = new MulticastSocket(MULTI_CAST_PORT)) {

                        multiSocket.setSoTimeout(TIMEOUT);
                        multiSocket.setNetworkInterface(networkInterface);
                        multiSocket.joinGroup(multicastAddress);

                        DatagramPacket dpSend = new DatagramPacket(DISCOVERY_MSG.getBytes(),
                                DISCOVERY_MSG.getBytes().length, multicastAddress, MULTI_CAST_PORT);
                        multiSocket.send(dpSend);

                        while (mSearching) {
                            byte[] buf = new byte[1024];

                            DatagramPacket dpRecv = new DatagramPacket(buf, buf.length);

                            try {
                                multiSocket.receive(dpRecv);
                                byte[] bytes = dpRecv.getData();
                                StringBuilder buffer = new StringBuilder();
                                for (int i = 0; i < dpRecv.getLength(); i++) {
                                    // parse /r
                                    if (bytes[i] == 13) {
                                        continue;
                                    }
                                    buffer.append((char) bytes[i]);
                                }

                                String receivedMessage = buffer.toString();

                                logger.debug("{}: got message: {}", TAG, receivedMessage);
                                // we can skip the request because we aren't interested in our own search broadcast
                                // message.
                                if (receivedMessage.startsWith("M-SEARCH")) {
                                    continue;
                                }

                                String[] infos = receivedMessage.split("\n");
                                Map<String, String> bulbInfo = new HashMap<>();
                                for (String info : infos) {
                                    int index = info.indexOf(':');
                                    if (index == -1) {
                                        continue;
                                    }
                                    String key = info.substring(0, index).trim();
                                    String value = info.substring(index + 1).trim();

                                    bulbInfo.put(key, value);
                                }
                                logger.debug("{}: got bulbInfo: {}", TAG, bulbInfo);
                                if (bulbInfo.containsKey("model") && bulbInfo.containsKey("id")) {
                                    DeviceBase device = DeviceFactory.build(bulbInfo);

                                    if (null == device) {
                                        logger.warn("Found unsupported device.");
                                        continue;
                                    }
                                    device.setDeviceName(bulbInfo.getOrDefault("name", ""));

                                    if (mDeviceList.containsKey(device.getDeviceId())) {
                                        updateDevice(mDeviceList.get(device.getDeviceId()), bulbInfo);
                                    }
                                    notifyDeviceFound(device);
                                }
                            } catch (SocketTimeoutException e) {
                                logger.debug("Error timeout: {}", e.getMessage(), e);
                            }

                        }
                    } catch (Exception e) {
                        if (!e.getMessage().contains("No IP addresses bound to interface")) {
                            logger.debug("Error getting ip addresses: {}", e.getMessage(), e);
                        }
                    }
                });
            }
        } catch (IOException e) {
            logger.debug("Error getting ip addresses: {}", e.getMessage(), e);
        }
    }

    private void notifyDeviceFound(DeviceBase device) {
        for (DeviceListener listener : mListeners) {
            listener.onDeviceFound(device);
        }
    }

    public void doAction(String deviceId, DeviceAction action) {
        DeviceBase device = mDeviceList.get(deviceId);
        if (device != null) {
            switch (action) {
                case open:
                    device.open(action.intDuration());
                    break;
                case close:
                    device.close(action.intDuration());
                    break;
                case brightness:
                    device.setBrightness(action.intValue(), action.intDuration());
                    break;
                case color:
                    device.setColor(action.intValue(), action.intDuration());
                    break;
                case colortemperature:
                    device.setCT(action.intValue(), action.intDuration());
                    break;
                case increase_bright:
                    device.increaseBrightness(action.intDuration());
                    break;
                case decrease_bright:
                    device.decreaseBrightness(action.intDuration());
                    break;
                case increase_ct:
                    device.increaseCt(action.intDuration());
                    break;
                case decrease_ct:
                    device.decreaseCt(action.intDuration());
                    break;
                default:
                    break;
            }
        }
    }

    public void doCustomAction(String deviceId, String action, String params) {
        DeviceBase device = mDeviceList.get(deviceId);
        device.sendCustomCommand(action, params);
    }

    public void addDevice(DeviceBase device) {
        mDeviceList.put(device.getDeviceId(), device);
    }

    public void updateDevice(DeviceBase device, Map<String, String> bulbInfo) {
        String[] addressInfo = bulbInfo.get("Location").split(":");
        device.setAddress(addressInfo[1].substring(2));
        device.setPort(Integer.parseInt(addressInfo[2]));
        device.setOnline(true);
        Color color = new Color(Integer.parseInt(bulbInfo.get("rgb")));
        DeviceStatus status = device.getDeviceStatus();
        status.setR(color.getRed());
        status.setG(color.getGreen());
        status.setB(color.getBlue());
        status.setCt(Integer.parseInt(bulbInfo.get("ct")));
        status.setHue(Integer.parseInt(bulbInfo.get("hue")));
        status.setSat(Integer.parseInt(bulbInfo.get("sat")));
    }

    public static String getDefaultName(DeviceBase device) {
        if (device.getDeviceModel() != null && !device.getDeviceName().equals("")) {
            return device.getDeviceName();
        }
        switch (device.getDeviceType()) {
            case ceiling:
            case ceiling1:
            case ceiling3:
                return "Yeelight LED Ceiling";
            case color:
                return "Yeelight Color LED Bulb";
            case mono:
                return "Yeelight White LED Bulb";
            case ct_bulb:
                return "Yeelight White LED Bulb v2";
            case stripe:
                return "Yeelight Color LED Stripe";
            case desklamp:
                return "Yeelight Mi LED Desk Lamp";
            default:
                return "";
        }
    }
}
