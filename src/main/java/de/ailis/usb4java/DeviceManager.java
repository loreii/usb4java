/*
 * Copyright (C) 2013 Klaus Reimer <k@ailis.de>
 * See LICENSE.md for licensing information.
 */

package de.ailis.usb4java;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.usb.UsbException;

import de.ailis.usb4java.descriptors.SimpleUsbDeviceDescriptor;
import de.ailis.usb4java.libusb.Context;
import de.ailis.usb4java.libusb.Device;
import de.ailis.usb4java.libusb.DeviceDescriptor;
import de.ailis.usb4java.libusb.DeviceList;
import de.ailis.usb4java.libusb.LibUSB;
import de.ailis.usb4java.libusb.LibUsbException;

/**
 * Manages the USB devices.
 * 
 * @author Klaus Reimer (k@ailis.de)
 */
final class DeviceManager
{
    /** The scan interval in milliseconds. */
    private static final int DEFAULT_SCAN_INTERVAL = 500;

    /** The logger. */
    private static final Logger LOG = Logger.getLogger(DeviceManager.class
        .getName());

    /** The virtual USB root hub. */
    private final RootHub rootHub;

    /** The libusb context. */
    private final Context context;

    /** If scanner already scanned for devices. */
    private boolean scanned = false;

    /** The currently connected devices. */
    private final Map<DeviceId, AbstractDevice> devices = Collections
        .synchronizedMap(new HashMap<DeviceId, AbstractDevice>());

    /**
     * Constructs a new device manager.
     * 
     * @param rootHub
     *            The root hub. Must not be null.
     * @throws UsbException
     *             When USB initialization fails.
     */
    DeviceManager(final RootHub rootHub) throws UsbException
    {
        if (rootHub == null)
            throw new IllegalArgumentException("rootHub must be set");
        this.rootHub = rootHub;
        this.context = new Context();
        final int result = LibUSB.init(this.context);
        if (result != 0)
            throw new LibUsbException("Unable to initialize libusb", result);
    }

    /**
     * Dispose the USB device manager. This exits the USB context opened by the
     * constructor.
     */
    public void dispose()
    {
        LibUSB.exit(this.context);
    }

    /**
     * Creates a device ID from the specified device.
     * 
     * @param device
     *            The libusb device.
     * @return The device id. Null if device is null or ID could not be build
     *         because an error occured while reading the device descriptor.
     *         Device should be ignored in this case.
     */
    private DeviceId createId(final Device device)
    {
        if (device == null) return null;
        final int busNumber = LibUSB.getBusNumber(device);
        final int addressNumber = LibUSB.getDeviceAddress(device);
        final int portNumber = LibUSB.getPortNumber(device);
        final DeviceDescriptor deviceDescriptor = new DeviceDescriptor();
        final int result = LibUSB.getDeviceDescriptor(device, deviceDescriptor);
        if (result < 0)
        {
            LOG.warning("Unable to get device descriptor for device " +
                addressNumber + " at bus " + busNumber + ": " +
                LibUSB.errorName(result));
            return null;
        }
        return new DeviceId(busNumber, addressNumber, portNumber,
            new SimpleUsbDeviceDescriptor(deviceDescriptor));
    }

    /**
     * Returns all currently connected devices.
     * 
     * @return The connected devices.
     * @throws LibUsbException
     *             When libusb reports an error while enumerating the devices.
     */
    private Set<AbstractDevice> getConnectedDevices() throws LibUsbException
    {
        final DeviceList devices = new DeviceList();
        final int result = LibUSB.getDeviceList(this.context, devices);
        if (result < 0)
            throw new LibUsbException("Unable to get USB device list",
                result);
        final Set<AbstractDevice> found = new HashSet<AbstractDevice>();
        try
        {
            for (Device libUsbDevice: devices)
            {
                final DeviceId id = createId(libUsbDevice);
                if (id == null) continue;

                AbstractDevice device = this.devices.get(id);
                if (device == null)
                {
                    final Device parent = LibUSB.getParent(libUsbDevice);
                    final DeviceId parentId = createId(parent);
                    final int speed = LibUSB.getDeviceSpeed(libUsbDevice);
                    final boolean isHub = id.getDeviceDescriptor()
                        .bDeviceClass() == LibUSB.CLASS_HUB;
                    if (isHub)
                    {
                        device = new Hub(this, id, parentId,
                            speed, libUsbDevice);
                    }
                    else
                    {
                        device = new NonHub(this, id,
                            parentId, speed, libUsbDevice);
                    }
                }
                found.add(device);
            }
        }
        finally
        {
            LibUSB.freeDeviceList(devices, true);
        }
        return found;
    }

    /**
     * Checks if the global device list contains any devices which are not in
     * the specified set of currently connected devices. These devices are
     * disconnected from their parent and then removed from the global device
     * list.
     * 
     * @param current
     *            The currently connected devices.
     */
    private void processRemovedDevices(final Set<AbstractDevice> current)
    {
        final Iterator<AbstractDevice> it = this.devices.values().iterator();
        while (it.hasNext())
        {
            final AbstractDevice device = it.next();
            if (!current.contains(device))
            {
                final AbstractDevice parent = this.devices.get(device.getId());
                if (parent == null)
                    this.rootHub.disconnectUsbDevice(device);
                else if (parent.isUsbHub())
                    ((Hub) parent).disconnectUsbDevice(device);
                it.remove();
            }
        }
    }

    /**
     * Checks for newly found devices which are not yet in the global list of
     * devices. These devices are added to their parent device and then added to
     * the global list of devices.
     * 
     * @param current
     *            The currently connected devices.
     */
    private void processNewDevices(final Set<AbstractDevice> current)
    {
        for (AbstractDevice device: current)
        {
            if (!this.devices.containsValue(device))
            {
                final DeviceId parentId = device.getParentId();
                if (parentId == null)
                    this.rootHub.connectUsbDevice(device);
                else
                {
                    final AbstractDevice parent = this.devices.get(parentId);
                    if (parent != null && parent.isUsbHub())
                    {
                        ((Hub) parent).connectUsbDevice(device);
                    }
                }
                this.devices.put(device.getId(), device);
            }
        }
    }

    /**
     * Scans the USB busses for new or removed devices.
     */
    public void scan()
    {
        try
        {
            final Set<AbstractDevice> found = getConnectedDevices();
            processRemovedDevices(found);
            processNewDevices(found);
        }
        catch (LibUsbException e)
        {
            throw new ScanException("Unable to scan USB devices: " + e, e);
        }
    }

    /**
     * Returns the libusb device for the specified id. The device must be freed
     * after use.
     * 
     * @param id
     *            The id of the device to return. Must not be null.
     * @return device The libusb device. Never null.
     * @throws DeviceNotFoundException
     *             When the device was not found.
     * @throws LibUsbException
     *             When libusb reported an error while enumerating USB devices.
     */
    public Device getLibUsbDevice(final DeviceId id) throws LibUsbException
    {
        if (id == null) throw new IllegalArgumentException("id must be set");

        final DeviceList devices = new DeviceList();
        final int result = LibUSB.getDeviceList(this.context, devices);
        if (result < 0)
            throw new LibUsbException("Unable to get USB device list",
                result);
        try
        {
            for (Device device: devices)
            {
                if (id.equals(createId(device)))
                {
                    LibUSB.refDevice(device);
                    return device;
                }
            }
        }
        finally
        {
            LibUSB.freeDeviceList(devices, true);
        }

        throw new DeviceNotFoundException(id);
    }

    /**
     * Releases the specified device.
     * 
     * @param device
     *            The device to release. Must not be null.
     */
    public void releaseDevice(final Device device)
    {
        if (device == null)
            throw new IllegalArgumentException("device must be set");
        LibUSB.unrefDevice(device);
    }

    /**
     * Starts scanning in the background.
     */
    public void start()
    {
        final Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    try
                    {
                        Thread.sleep(DEFAULT_SCAN_INTERVAL);
                    }
                    catch (final InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    scan();
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Scans for devices but only if this was not already done.
     */
    public void firstScan()
    {
        if (!this.scanned) scan();
    }
}
