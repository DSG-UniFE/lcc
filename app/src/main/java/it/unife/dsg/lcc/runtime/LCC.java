package it.unife.dsg.lcc.runtime;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;

import it.unife.dsg.lcc.MainActivity;
import it.unife.dsg.lcc.R;
import it.unife.dsg.lcc.configuration.BluetoothTethering;
import it.unife.dsg.lcc.configuration.WifiAccessManager;
import it.unife.dsg.lcc.util.Constants;
import it.unife.dsg.lcc.util.Utils;


public class LCC extends Thread {
	
//	private static LCC lcc = null;
	private boolean active = true;
	private String networkSSID, prefixNetworkSSID;
	private Context context;
	private BluetoothTethering bt;
	private BluetoothDevice currentBtHotspot;
	private ScanResult currentWifiHotspot;
	private Handler uiHandler;

	private HotspotType hotspotType;
    private LCCRole role;
    private LCCRole currentRole;
	private int changeRolePeriod;    //seconds, hotspot --> client and client --> hotspot
	private int changeHotspotPeriod; //seconds, when device has client role
	private int maxTimeWaitToBecomeHotspot;

    private NotificationManager notificationManager;
    // Unique Identification Number for the Notification.
    // We use it on Notification start, and to cancel it.
    static private final int WIFI_ACTIVE_NOTIFICATION_ID = Constants.WIFI_NOTIFICATION_ID;
    static private final int BLUETOOTH_ACTIVE_NOTIFICATION_ID = Constants.BLUETOOTH_NOTIFICATION_ID;


	// Getters and Setters
	private int getChangeRolePeriod() {

        return changeRolePeriod;
	}

	private void setChangeRolePeriod(int changeRolePeriod) {

        this.changeRolePeriod = changeRolePeriod;
	}

	private int getHotspotPeriod() {

        return changeHotspotPeriod;
	}

	private void setHotspotPeriod(int changeHotspotPeriod) {

        this.changeHotspotPeriod = changeHotspotPeriod;
	}

	private HotspotType getHotspotType() {

        return hotspotType;
	}

	private void setHotspotType(HotspotType hotspotType) {

        this.hotspotType = hotspotType;
	}

    private LCCRole getCurrentRole() {
        return currentRole;
    }

    private void setCurrentRole(LCCRole currentRole) {
        this.currentRole = currentRole;
    }

    private LCCRole getRole() {
        return role;
    }

    private void setRole(LCCRole role) {
        this.role = role;
    }

    //getInstance
//	public static synchronized LCC getInstance(boolean forceStart, Context context,
//			LCCRole initialRole, HotspotType hotspotType) {
//		if (forceStart && lcc == null) {
//			lcc = new LCC(context, initialRole, hotspotType);
//			
//			lcc.start();
//				
//			System.out.println("LCC " + connection + ": ENABLED");
//		}
//		else
//		{
//			lcc.context = context;
//			lcc.currentRole = initialRole;
//			lcc.hotspotType = hotspotType;
//		}
//		
//		return lcc;
//	}
	
	public LCC(Context context, LCCRole role, HotspotType hotspotType, int rs, int hc,
               int maxTimewaitToBecomeHotspot, Handler uiHandler) {

        this.context = context;
        setCurrentRole(role);
        setRole(role);
        setHotspotType(hotspotType);
        setChangeRolePeriod(rs); // regular = 450
        setHotspotPeriod(hc); // regular = 150
        this.maxTimeWaitToBecomeHotspot = maxTimewaitToBecomeHotspot; // regular = 30
        this.uiHandler = uiHandler;

        prefixNetworkSSID = "RAMP_hotspot_";
        networkSSID = prefixNetworkSSID + Utils.nextRandomNonNegativeShort();
        bt = null;
        currentBtHotspot = null;
        currentWifiHotspot = null;

        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        try {
            boolean foundHotspotId = false;
            String[] filenameLists = context.fileList();
            for(String filename : filenameLists) {
                if (filename.equals(Constants.HOTSPOT_ID)) {
                    foundHotspotId = true;
                    break;
                }
            }

            if (foundHotspotId) {
                FileInputStream inputStream = context.openFileInput(Constants.HOTSPOT_ID);
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                networkSSID = reader.readLine();
                reader.close();
                inputStream.close();
                System.out.println("LCC " + hotspotType + ": letto file, networkSSID: " + networkSSID);
            } else {
                FileOutputStream fOut = context.openFileOutput(Constants.HOTSPOT_ID, Context.MODE_PRIVATE);
                fOut.write(networkSSID.getBytes());
                fOut.close();
                System.out.println("LCC " + hotspotType + ": scritto file, networkSSID: " + networkSSID);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

		this.start();
	}
	
	@Override
	public void run() {
		try {
			System.out.println("LCC " + hotspotType + ": START: initial role " + currentRole.toString());
			Utils.appendLog("LCC " + hotspotType + " START: initial role " + currentRole.toString());

            showNotification();
            sendIntentBroadcast(Constants.MESSAGE_LCC_ACTIVATE);

			// Set initial role
            switch (getRole()) {
                case CLIENT:
                    setClient();
                    setCurrentRole(role);
                    break;
                case HOTSPOT:
                    setHotspot();
                    setCurrentRole(role);
                    break;
                case CLIENT_HOTSPOT:
                    setClient();
                    setCurrentRole(LCCRole.CLIENT);
                    break;
                default:
                    setClient();
                    setCurrentRole(LCCRole.CLIENT);
                    break;
            }
            sendMessage(false);

            System.out.println("LCC " + hotspotType.toString() + ": " + "changeRolePeriod "
					+ changeRolePeriod);
            System.out.println("LCC " + hotspotType.toString() + ": " + "changeHotspotPeriod "
					+ changeHotspotPeriod);
			long currentTimeChangeRole = getChangeRolePeriod() * 1000; //ms
			long currentTimeChangeHotspot = getHotspotPeriod() * 1000; //ms
			long sleep = 5000;

			while (active) {
				long preResolve = System.currentTimeMillis();
                System.out.println("LCC " + hotspotType.toString() + ": " +
                        "currentTimeChangeRole " + currentTimeChangeRole);
                System.out.println("LCC " + hotspotType.toString() + ": " +
                        "currentTimeChangeHotspot " + currentTimeChangeHotspot);
                System.out.println("LCC " + hotspotType.toString() + ": " +
                        "role " + currentRole);

				if(currentTimeChangeRole <= 0) {
					Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                            "Timeout changeRolePeriod");
                    System.out.println("LCC " + hotspotType.toString() + ": " +
                            "Timeout changeRolePeriod");

                    if (getRole() == LCCRole.CLIENT_HOTSPOT)
                        changeRole();

                    // Restart counter
                    currentTimeChangeRole = getChangeRolePeriod() * 1000;
                    currentTimeChangeHotspot = changeHotspotPeriod * 1000;
				} else {
					if(currentTimeChangeHotspot <= 0) {
						Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                                "Timeout changeHotspotPeriod");
                        System.out.println("LCC " + hotspotType.toString() + ": " +
                                "Timeout changeHotspotPeriod");
                        if (getCurrentRole() == LCCRole.CLIENT)
                            changeHotspot();

                        // Restart counter
                        currentTimeChangeHotspot = changeHotspotPeriod * 1000;
					} else {
                        // CLIENT: currentTimeChangeHotspot > 0 && currentTimeChangeRole > 0
                        boolean notFound;

                        switch (getRole()) {
                            case CLIENT:
                                //Check connection to hotspot
                                notFound = checkHotspotConnection();
                                //boolean notFound = setClient();

                                if(notFound) {
                                    Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                                            "checkHotspotConnection() not found connection!");
                                    System.out.println("LCC " + hotspotType.toString() + ": " +
                                            "checkHotspotConnection() not found connection!");

                                    // After a random time retry
                                    int randomTime = Utils.nextRandomInt(maxTimeWaitToBecomeHotspot);

                                    Utils.appendLog("LCC " + hotspotType.toString() + ": " + "wait " +
                                            randomTime + " seconds before retry...");
                                    System.out.println("LCC " + hotspotType.toString() + ": " + "wait " +
                                            randomTime + " seconds before retry...");

                                    Thread.sleep(randomTime * 1000);

                                    // First new checkHotspotConnection
                                    notFound = checkHotspotConnection();
                                    if (!notFound) {
                                        boolean changedHotspot = changeHotspot();
                                        if (changedHotspot)
                                            // Restart counter
                                            currentTimeChangeHotspot = changeHotspotPeriod * 1000;
                                    }
                                }
                                break;

                            case CLIENT_HOTSPOT:
                                if (getCurrentRole() == LCCRole.CLIENT) {
                                    // Check connection to hotspot
                                    notFound = checkHotspotConnection();
//                                    boolean notFound = setClient();

                                    if (notFound) {
                                        Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                                                "checkHotspotConnection() not found connection!");
                                        System.out.println("LCC " + hotspotType.toString() + ": " +
                                                "checkHotspotConnection() not found connection!");

                                        // After a random time changeRole to become hotspot
                                        int randomTime = Utils.nextRandomInt(maxTimeWaitToBecomeHotspot);

                                        Utils.appendLog("LCC " + hotspotType.toString() + ": " + "wait " +
                                                randomTime + " seconds before change role...");
                                        System.out.println("LCC " + hotspotType.toString() + ": " + "wait " +
                                                randomTime + " seconds before change role...");

                                        Thread.sleep(randomTime * 1000);

                                        notFound = checkHotspotConnection(); //First new checkHotspotConnection!
                                        if (notFound) {
                                            boolean changedRole = changeRole();
                                            if (changedRole)
                                                currentTimeChangeRole = changeRolePeriod * 1000; //restart counter
                                        }
                                    }
                                }
                                break;

                            default:
                                break;
                        }
					}
				}
				
				long elapsedResolve = System.currentTimeMillis() - preResolve;
				if (sleep - elapsedResolve > 0)
					Thread.sleep(sleep - elapsedResolve);
				
				// Update counters
				elapsedResolve = System.currentTimeMillis() - preResolve;
				currentTimeChangeRole = currentTimeChangeRole - elapsedResolve;
				currentTimeChangeHotspot = currentTimeChangeHotspot - elapsedResolve;

                if (currentBtHotspot != null)
                    System.out.println("LCC " + hotspotType.toString() + ": " + "currentBtHotspot " +
                            currentBtHotspot.getName());

                // Update UI
                System.out.println("LCC " + hotspotType.toString() + ": " + "before sendMessage()");
                sendMessage(false);
                System.out.println("LCC " + hotspotType.toString() + ": " + "after sendMessage()");
			}
			System.out.println("LCC " + hotspotType + ": FINISHED");
			Utils.appendLog("LCC " + hotspotType + ": FINISHED");
		} catch (InterruptedException ie) {
			System.out.println("LCC " + hotspotType + ": InterruptedException");
            sendIntentBroadcast(Constants.MESSAGE_LCC_DEACTIVATE);
		} catch (Exception e) {
            System.out.println("LCC " + hotspotType + " ERROR: Exceptions");
			e.printStackTrace();
		}
        sendIntentBroadcast(Constants.MESSAGE_LCC_DEACTIVATE);
//		wifiOpp = null;
		System.out.println("LCC " + hotspotType + ": END");
		Utils.appendLog("LCC " + hotspotType + ": END");
	}

	private boolean checkHotspotConnection() {
		boolean notFound = false;

		if (getHotspotType() == HotspotType.WIFI) {
			if(currentWifiHotspot == null) {
				// No connected to hotspot WIFI
				currentWifiHotspot = WifiAccessManager.connectToWifiAp(context, prefixNetworkSSID, "");
				notFound = currentWifiHotspot == null;
			}

//			if(currentWifiHotspot == null || !WifiAccessManager.isWifiApConnect(context, currentWifiHotspot.SSID))
//			{
//				//No connected to hotspot WIFI
//				currentWifiHotspot = WifiAccessManager.connectToWifiAp(context, prefixNetworkSSID, "");
//				notFound = currentWifiHotspot == null;
//			}
			
		} else if(getHotspotType() == HotspotType.BLUETOOTH) {
			if(currentBtHotspot == null) {
				if(bt == null)	
					bt = new BluetoothTethering(context);
				
				// No connected to hotspot BLUETOOTH
				currentBtHotspot = bt.startConnection(prefixNetworkSSID, "");
				notFound = currentBtHotspot == null;
			}
		}
		return notFound;
	}
	
	private boolean changeRole() {
		boolean res = false;
		Utils.appendLog("LCC " + hotspotType.toString() + ": " + "changeRole()");
        System.out.println("LCC " + hotspotType.toString() + ": " + "changeRole()");

        switch (getCurrentRole()) {
            case CLIENT:
                // client --> hotspot
                res = setHotspot();
                if (res) {
                    // My role is hotspot
                    setCurrentRole(LCCRole.HOTSPOT);
                }

                currentBtHotspot = null;
                currentWifiHotspot = null;
                break;

            case HOTSPOT:
                // hotspot --> client
                res = setClient();
                if (res) {
                    // My role is client
                    setCurrentRole(LCCRole.CLIENT);
                }
                break;
        }
		
		if (res) {
            Utils.appendLog("LCC " + hotspotType.toString() + ": " + "Changed role to " +
                    currentRole.toString());
            System.out.println("LCC " + hotspotType.toString() + ": " + "Changed role to " +
                    currentRole.toString());
            sendIntentBroadcast(Constants.MESSAGE_ROLE_CHANGED);
        } else {
            Utils.appendLog("LCC " + hotspotType.toString() + ": " + "Role not changed!");
            System.out.println("LCC " + hotspotType.toString() + ": " + "Role not changed!");
        }

		return res;
	}

	private boolean setClient() {
        boolean res = false;
		Utils.appendLog("LCC " +  hotspotType.toString() + ": " + "setClient()");
        System.out.println("LCC " +  hotspotType.toString() + ": " + "setClient()");
		
		if (getHotspotType() == HotspotType.WIFI) {
			currentWifiHotspot = WifiAccessManager.connectToWifiAp(context, prefixNetworkSSID, "");
			res = currentWifiHotspot != null;
			
			if (res) {
                Utils.appendLog("LCC " + hotspotType.toString() + ": " + "Connected to hotspot " +
                        hotspotType.toString() + " with SSID: " + currentWifiHotspot.SSID);
                System.out.println("LCC " + hotspotType.toString() + ": " + "Connected to hotspot " +
                        hotspotType.toString() + " with SSID: " + currentWifiHotspot.SSID);
            }
		} else if(getHotspotType() == HotspotType.BLUETOOTH) {
			if(bt == null)	
				bt = new BluetoothTethering(context);
			
			currentBtHotspot = bt.startConnection(prefixNetworkSSID, "");
			res = currentBtHotspot != null;
			
			if (res) {
                Utils.appendLog("LCC " + hotspotType.toString() + ": " + "Connected to hotspot "
                        + hotspotType.toString() + " with name: " + currentBtHotspot.getName());
                System.out.println("LCC " + hotspotType.toString() + ": " + "Connected to hotspot "
                        + hotspotType.toString() + " with name: " + currentBtHotspot.getName());
            }
		}
		return res;
	}

	private boolean setHotspot() {
		boolean res = false;
		Utils.appendLog("LCC " + hotspotType.toString() + ": " + "setHotspot()");
        System.out.println("LCC " + hotspotType.toString() + ": " + "setHotspot()");

        switch (getHotspotType()) {
            case BLUETOOTH:
                if(bt == null)
                    bt = new BluetoothTethering(context);

                bt.restartBluetooth();
                bt.setBluetoothTethering(true, networkSSID);
                res = bt.isBluetoothTetheringEnabled();
                break;

            case WIFI:
                res = WifiAccessManager.setWifiApState(context, networkSSID, true);
                break;
        }

		if (res) {
            Utils.appendLog("LCC " + hotspotType.toString() + ": " + "Activate hotspot " +
                    hotspotType.toString() + " with SSID " + networkSSID);
            System.out.println("LCC " + hotspotType.toString() + ": " + "Activate hotspot " +
                    hotspotType.toString() + " with SSID " + networkSSID);
        } else {
            Utils.appendLog("LCC " + hotspotType.toString() + ": " + "IMPOSSIBLE activate hotspot " +
                    hotspotType.toString());
            System.out.println("LCC " + hotspotType.toString() + ": " + "IMPOSSIBLE activate hotspot " +
                    hotspotType.toString());
        }

		return res;
	}
	
	private boolean changeHotspot() {
		boolean res = false;
		Utils.appendLog("LCC " + hotspotType.toString() + ": " + "changeHotspot()");
        System.out.println("LCC " + hotspotType.toString() + ": " + "changeHotspot()");
		
		if(getCurrentRole() == LCCRole.CLIENT) {
			// Client no change role, but can change hotspot
			if(getHotspotType() == HotspotType.WIFI) {
				ScanResult result = WifiAccessManager.connectToWifiAp(context, prefixNetworkSSID, "");
				
				// Test if i lost connection
				if(result == null) {
					currentWifiHotspot = null;
					Utils.appendLog("LCC " +  hotspotType.toString() + ": " +
                            "Lost connection! No hotspots found...");
                    System.out.println("LCC " +  hotspotType.toString() + ": " +
                            "Lost connection! No hotspots found...");
				}
				
				if(result != null && currentWifiHotspot != null &&
                        result.SSID.equals(currentWifiHotspot.SSID)) {
					//I'm connect to the same hotspot wifi. Try to connect to a different hotspot
					result = WifiAccessManager.connectToWifiAp(context, prefixNetworkSSID, currentWifiHotspot.SSID);
				    if(result != null) {
				    	//Ok, client change hotspot
				    	currentWifiHotspot = result;
				    	res = true;
				    	Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                                "Change hotspot " + hotspotType.toString() + ", connected to " +
                                currentWifiHotspot.SSID);
                        System.out.println("LCC " + hotspotType.toString() + ": " +
                                "Change hotspot " + hotspotType.toString() + ", connected to " +
                                currentWifiHotspot.SSID);
				    } else {
				    	//else, connected to the same hotspot
				    	Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                                "Connected to the same hotspot " + hotspotType.toString());
                        System.out.println("LCC " + hotspotType.toString() + ": " +
                                "Connected to the same hotspot " + hotspotType.toString());
				    }		    
				}
				
//				//Test if i lost connection
//				boolean connected = false;
//				if(currentWifiHotspot != null && WifiAccessManager.isWifiApConnect(context, currentWifiHotspot.SSID))
//					connected = true;
//				
//				if(connected)
//				{
//					//I'm connect to the same hotspot wifi. Try to connect to a different hotspot
//					ScanResult result = WifiAccessManager.connectToWifiAp(context, prefixNetworkSSID, currentWifiHotspot.SSID);
//				    if(result != null)
//				    {
//				    	//Ok, client change hotspot
//				    	currentWifiHotspot = result;
//				    	res = true;
//				    }
//				    //else, connected to the same hotspot
//				}
			} else if(getHotspotType() == HotspotType.BLUETOOTH) {
				if(bt == null)	
	    			bt = new BluetoothTethering(context);
				
//				BluetoothDevice result = bt.startConnection(prefixNetworkSSID, "");
//				
//				//Test if i lost connection
//				if(result == null)
//					currentBtHotspot = null;
//				
//				if(result != null && currentBtHotspot != null && 
//						result.getName().equals(currentBtHotspot.getName()))
//				{
//					//I'm connect to the same hotspot bluetooth. Try to connect to a different hotspot
//					result = bt.startConnection(prefixNetworkSSID, currentBtHotspot.getName());
//					if(result != null)
//				    {
//				    	//Ok, client change hotspot
//						currentBtHotspot = result;
//						res = true;
//				    }
//				    //else, connected to the same hotspot
//				}
				
//				if(currentBtHotspot != null)
//					currentBtHotspot = bt.startConnection(prefixNetworkSSID, currentBtHotspot.getName());
//				else
//					currentBtHotspot = bt.startConnection(prefixNetworkSSID, "");
//				if(currentBtHotspot != null)
//				{
//					    //Ok, client change hotspot
//					res = true;
//				}

				//Test if i lost connection
				boolean connected = false;
				if(currentBtHotspot != null) {
					if(bt.isConnectToDevice(currentBtHotspot))
					      connected = true;
					else
						currentBtHotspot = null;
				}
				
				if(connected) {
					// I'm connect to the same hotspot bluetooth. Try to connect to a different hotspot
					BluetoothDevice result = bt.startConnection(prefixNetworkSSID, currentBtHotspot.getName());
					if(result != null) {
				    	// Ok, client change hotspot
						currentBtHotspot = result;
						res = true;
						Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                                "Change hotspot " + hotspotType.toString() + ", connected to " +
                                currentBtHotspot.getName());
                        System.out.println("LCC " + hotspotType.toString() + ": " +
                                "Change hotspot " + hotspotType.toString() + ", connected to " +
                                currentBtHotspot.getName());
				    } else {
                        // else, connected to the same hotspot
				    	Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                                "Connected to the same hotspot " + hotspotType.toString());
                        System.out.println("LCC " + hotspotType.toString() + ": " +
                                "Connected to the same hotspot " + hotspotType.toString());
					}
				} else {
					Utils.appendLog("LCC " + hotspotType.toString() + ": " +
                            "Lost connection! No hotspots found...");
                    System.out.println("LCC " + hotspotType.toString() + ": " +
                            "Lost connection! No hotspots found...");
				}
			}
		}
        if (res)
            sendIntentBroadcast(Constants.MESSAGE_HOTSPOT_CHANGED);
		return res;
	}

	public void deactivate() {
		System.out.println("LCC " + hotspotType + ": DISABLED");
		Utils.appendLog("LCC " + hotspotType + ": DISABLED");

        this.active = false;
        interrupt();

        removeNotification();
        sendMessage(true);

        WifiAccessManager.setWifiApState(context, networkSSID, false);
        if (bt != null)
            bt.setBluetoothTethering(false, "");
	}
	
	public enum HotspotType {
	    WIFI, BLUETOOTH
	}
	
	public enum LCCRole {
	    CLIENT, HOTSPOT, CLIENT_HOTSPOT
	}

    /**
     * Show a notification while thread is running.
     */
    private void showNotification() {
        System.out.println("LCC " + hotspotType + ": showNotification()");
        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), 0);
        // initialize the Notification
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(context)
                        .setSmallIcon(R.drawable.ic_stat_wifi)
                        .setContentTitle("LCC")
                        .setTicker("LCC started")
                        .setContentIntent(contentIntent)
                        .setOngoing(true);
        switch (hotspotType) {
			case WIFI:
                notificationBuilder.setWhen(System.currentTimeMillis()).setContentText("WifiThread is active");
                // notifyID allows you to update the notification later on.
                notificationManager.notify(WIFI_ACTIVE_NOTIFICATION_ID, notificationBuilder.build());
                break;
			case BLUETOOTH:
                notificationBuilder.setWhen(System.currentTimeMillis()).setContentText("BluetoothThread is active");
                // notifyID allows you to update the notification later on.
                notificationManager.notify(BLUETOOTH_ACTIVE_NOTIFICATION_ID, notificationBuilder.build());
                break;
            default:
                break;
        }
    }


    private void removeNotification() {
        System.out.println("LCC " + hotspotType + ": removeNotification()");
        switch (hotspotType) {
			case WIFI:
                notificationManager.cancel(WIFI_ACTIVE_NOTIFICATION_ID);
                break;
			case BLUETOOTH:
                notificationManager.cancel(BLUETOOTH_ACTIVE_NOTIFICATION_ID);
                break;
            default:
                break;
        }
    }


    private void sendIntentBroadcast(int message_id) {
        Intent intent = new Intent(Constants.LCC_INTENT_ACTION);
//        intent.setAction(Constants.WIFIOPP_INTENT_ACTION);
        intent.putExtra("data", message_id);
        context.sendBroadcast(intent);
    }

    private void sendMessage(boolean deactivate) {
        System.out.println("LCC " + hotspotType.toString() + ": sendMessage()");
        HashMap<String,String> info = new HashMap<String, String>();
        info.put("network_id", networkSSID);
        info.put("updated", Utils.getDate());

        Message msg;
        switch (hotspotType) {
            case BLUETOOTH:
                System.out.println("LCC " + hotspotType.toString() + ": sendMessage() BLUETOOTH");
                msg = uiHandler.obtainMessage(2);
                switch (currentRole) {
                    case CLIENT:
                        System.out.println("LCC " + hotspotType.toString() + ": sendMessage() BLUETOOTH - CLIENT");
                        info.put("bluetooth_role", "client");
                        info.put("bluetooth_connected_to", "none");
                        if (currentBtHotspot != null) {
                            System.out.println(("LCC " + hotspotType + ": sendMessage(), currentBtHotspot" + currentBtHotspot.getName()));
                            info.put("bluetooth_connected_to", currentBtHotspot.getName());
                        } else {
                            System.out.println(("LCC " + hotspotType + ": sendMessage(), currentBtHotspot NONE"));
                            info.put("bluetooth_connected_to", "none");
                        }
                        break;

                    case HOTSPOT:
                        System.out.println("LCC " + hotspotType.toString() + ": sendMessage() BLUETOOTH - HOTSPOT");
                        info.put("bluetooth_role", "hotspot");
                        break;
                    default:
                        info.put("bluetooth_role", "none");
                        info.put("bluetooth_connected_to", "none");
                        break;
                }
                break;

            case WIFI:
                System.out.println("LCC " + hotspotType.toString() + ": sendMessage() WIFI");
                msg = uiHandler.obtainMessage(1);
                switch (currentRole) {
                    case CLIENT:
                        System.out.println("LCC " + hotspotType.toString() + ": sendMessage() WIFI - CLIENT");
                        info.put("wifi_role", "client");
                        if (currentWifiHotspot != null)
                            info.put("wifi_connected_to", currentWifiHotspot.SSID);
                        else
                            info.put("wifi_connected_to", "none");
                        break;

                    case HOTSPOT:
                        System.out.println("LCC " + hotspotType.toString() + ": sendMessage() WIFI - HOTSPOT");
                        info.put("wifi_role", "hotspot");
                        info.put("wifi_connected_to", "none");
                        break;

                    default:
                        info.put("wifi_role", "none");
                        info.put("wifi_connected_to", "none");
                        break;
                }
                break;

            default:
                msg = uiHandler.obtainMessage(0);
                info.put("wifi_role", "none");
                info.put("wifi_connected_to", "none");
                info.put("bluetooth_role", "none");
                info.put("bluetooth_connected_to", "none");
                break;
        }

        if (deactivate) {
            switch (hotspotType) {
                case BLUETOOTH:
                    info.put("bluetooth_role", "none");
                    info.put("bluetooth_connected_to", "none");
                    break;
                case WIFI:
                    info.put("wifi_role", "none");
                    info.put("wifi_connected_to", "none");
                    break;
                default:
                    info.put("wifi_role", "none");
                    info.put("wifi_connected_to", "none");
                    info.put("bluetooth_role", "none");
                    info.put("bluetooth_connected_to", "none");
                    break;
            }
        }

        msg.obj = info;

        uiHandler.sendMessage(msg);
    }

}
