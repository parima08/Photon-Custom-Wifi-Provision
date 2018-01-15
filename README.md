# Photon-Custom-Wifi-Provision

PAL Assumptions
This is the working specification for the Mobile Library targeted towards the demo. This mobile library is purely a data model layer - it does not provide any UI components. Changes are likely as UX cases are in progress.
Wifi Provisioning:
- The client side is doing the wifi scan and selection.
Registration:
- The client is handling account selection.
Data Model
The PiggyBank object provides an abstraction layer that allows the app to communicate with the Device Cloud (and in turn the device). A PiggyBank object is created and returned either when provisioning the device (by calling  wifiProvision ) before it has been configured or when getting a configured PiggyBank (by calling  getPig ).
The PiggyBank object uses an “on-demand” synchronization design where the client can read and write the local object and decide independently when to read and write the object to the Device cloud.
In particular, getters ( getQuietMode , g  etBalance ,  getGoal ) and queries ( isAccountRegistered ,i  sWifiProvisioned ,i  sOnline )returninformationaboutthelocal object. In order to get the latest representation of the device, the client must call p  ullFromCloud beforehand.
And setters ( setQuietMode  and  setGoal ) update the local object with the new settings. In order for this information to be pushed to the Device Cloud (and in turn, the device), the client must call pushToCloud  after setting parameters.

Threading Model
The PAL provides blocking methods that encapsulate all interactions with the Device Cloud. These remote, blocking calls should not be run on the UI thread. The current library design relies on the client to run these calls on a worker thread. (We can revisit this design choice based on feedback from the client side).
In contrast, all the methods for interaction with the local PiggyBank instance are non-blocking and thread-safe.
