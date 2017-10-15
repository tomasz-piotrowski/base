/*
 * Copyright (C) 2017  Jonas Zeiger <jonas.zeiger@talpidae.net>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.talpidae.base.insect;

import com.google.common.base.Strings;
import com.google.common.eventbus.EventBus;
import com.google.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.talpidae.base.event.Invalidate;
import net.talpidae.base.event.Shutdown;
import net.talpidae.base.insect.config.SlaveSettings;
import net.talpidae.base.insect.message.payload.Mapping;
import net.talpidae.base.insect.state.InsectState;
import net.talpidae.base.insect.state.ServiceState;
import net.talpidae.base.util.network.NetworkUtil;

import javax.inject.Inject;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static net.talpidae.base.util.arrays.Arrays.swap;


@Singleton
@Slf4j
public class SyncSlave extends Insect<SlaveSettings> implements Slave
{
    private static final long DEPENDENCY_RESEND_MILLIES_MIN = TimeUnit.MILLISECONDS.toMillis(100);

    private static final long DEPENDENCY_RESEND_MILLIES_MAX = TimeUnit.SECONDS.toMillis(12);

    private final Map<String, RouteWaiter> dependencies = new ConcurrentHashMap<>();

    private final EventBus eventBus;

    private final long pulseDelayCutoff;

    private final SomewhatRandom somewhatRandom = new SomewhatRandom();

    private final NetworkUtil networkUtil;

    private long nextHeartBeatNanos = 0L;

    @Getter
    private volatile boolean isRunning = false;

    @Inject
    public SyncSlave(SlaveSettings settings, EventBus eventBus, NetworkUtil networkUtil)
    {
        super(settings, true);

        this.eventBus = eventBus;
        this.networkUtil = networkUtil;

        this.pulseDelayCutoff = TimeUnit.MILLISECONDS.toNanos(settings.getPulseDelay() + settings.getPulseDelay() / 2);
    }


    @Override
    public void run()
    {
        try
        {
            synchronized (this)
            {
                isRunning = true;
                notifyAll();
            }

            if (Strings.isNullOrEmpty(getSettings().getRoute()))
            {
                log.debug("argument for parameter \"route\" is empty, won't publish anything");
            }

            nextHeartBeatNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(getSettings().getPulseDelay());
            super.run();
        }
        finally
        {
            isRunning = false;
        }
    }

    /**
     * Try to find a service for route, register route as a dependency and block in case it isn't available immediately.
     */
    @Override
    public InetSocketAddress findService(String route) throws InterruptedException
    {
        return findService(route, Long.MAX_VALUE);
    }

    /**
     * Try to find a service for route, register route as a dependency and block in case it isn't available immediately.
     *
     * @return Address of discovered service if one was discovered before a timeout occurred, null otherwise.
     */
    @Override
    public InetSocketAddress findService(String route, long timeoutMillies) throws InterruptedException
    {
        // we may occasionally get an empty collection from findServices()
        val alternatives = findServices(route, timeoutMillies);
        if (!alternatives.isEmpty())
        {
            // we know the list is not empty and shuffled already
            return alternatives.get(0).getSocketAddress();
        }

        // timeout
        return null;
    }

    /**
     * Return all known services for route, register route as a dependency and block in case there are none available immediately.
     *
     * @return Discovered services if any were discovered before a timeout occurred, empty list otherwise.
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    @Override
    public List<? extends ServiceState> findServices(String route, long timeoutMillies) throws InterruptedException
    {
        List<ServiceState> alternatives = lookupServices(route);
        if (alternatives.size() > 0)
        {
            // fast path
            return alternatives;
        }

        long now = System.nanoTime();
        val timeout = (timeoutMillies >= 0) ? TimeUnit.NANOSECONDS.toMillis(now) + timeoutMillies : Long.MAX_VALUE;
        long waitInterval = DEPENDENCY_RESEND_MILLIES_MIN;

        val routeWaiter = dependencies.computeIfAbsent(route, k -> new RouteWaiter());
        do
        {
            // indicate that we are waiting for this route to be discovered
            switch (routeWaiter.advanceDiscoveryState(now))
            {
                case SEND:
                    // send out discovery request
                    requestDependency(route);

                    // fall-through

                case DONE:
                    alternatives = lookupServices(route);
                    if (alternatives.size() > 0)
                    {
                        routeWaiter.setDiscoveryComplete();
                        dependencies.remove(route);

                        return alternatives;
                    }
            }

            // wait for news on this route
            val maxRemainingMillies = timeout - TimeUnit.NANOSECONDS.toMillis(now);
            val waitMillies = Math.min(Math.min(waitInterval, maxRemainingMillies), DEPENDENCY_RESEND_MILLIES_MAX);
            if (waitMillies >= 0L)
            {
                synchronized (routeWaiter)
                {
                    routeWaiter.wait(waitMillies);
                }
            }
            else
            {
                break;
            }

            waitInterval = waitInterval * 2;
            now = System.nanoTime();
        }
        while (true);

        log.warn("findService(): timeout for route: {}", route);
        return alternatives;
    }


    private List<ServiceState> lookupServices(String route)
    {
        val services = getRouteToInsects().getOrDefault(route, EMPTY_ROUTE);

        // need to iterate over all services for this route anyways
        val alternatives = services.getInsects();
        if (alternatives.length > 0)
        {
            // the InsectCollection is already sorted by timestamp, perfect for us
            val timestampCutOff = alternatives[0].getTimestamp() - pulseDelayCutoff;
            int i;
            for (i = 1; i < alternatives.length; ++i)
            {
                if (alternatives[i].getTimestamp() < timestampCutOff)
                {
                    // cutoff reached
                    break;
                }
            }

            // since we keep the original array immutable, we need to clone it
            val validAlternatives = new ServiceState[i];
            System.arraycopy(alternatives, 0, validAlternatives, 0, i);

            // shuffle
            for (int j = i; i > 1; i--)
            {
                swap(validAlternatives, i - 1, somewhatRandom.nextInt(i));
            }

            return Arrays.asList(validAlternatives);
        }

        return Collections.emptyList();
    }


    @Override
    protected void postHandleMapping(InsectState state, Mapping mapping, boolean isNewMapping)
    {
        if (isNewMapping)
        {
            // notify findService() callers blocking for route discovery
            val routeWaiter = dependencies.get(mapping.getRoute());
            if (routeWaiter != null)
            {
                routeWaiter.setDiscoveryComplete();
                dependencies.remove(mapping.getRoute());
            }
        }
    }

    @Override
    protected void handleShutdown()
    {
        // tell listeners that we received a shutdown request
        eventBus.post(new Shutdown());
    }

    @Override
    protected void handleInvalidate()
    {
        // drop cached remotes
        getRouteToInsects().values().forEach(InsectCollection::clear);

        // tell listeners that we received an invalidate request
        eventBus.post(new Invalidate());
    }


    @Override
    protected long handlePulse()
    {
        long now = System.nanoTime();
        if (now >= nextHeartBeatNanos)
        {
            sendHeartbeat();

            // scheduled next heartbeat, taking overshoot (delay) of this heartbeat into account
            nextHeartBeatNanos += getPulseDelayNanos() - Math.max(1L, (now - nextHeartBeatNanos));
        }

        return Math.max(1L, nextHeartBeatNanos - now);
    }


    private void sendHeartbeat()
    {
        val settings = getSettings();

        val bindSocketAddress = settings.getBindAddress();
        val hostAddress = settings.getBindAddress().getAddress();
        val port = settings.getBindAddress().getPort();

        for (val remote : settings.getRemotes())
        {
            val remoteAddress = remote.getAddress();

            final String host = (hostAddress != null)
                    ? networkUtil.getReachableLocalAddress(hostAddress, remoteAddress).getHostAddress()
                    : bindSocketAddress.getHostString();

            val heartBeatMapping = Mapping.builder()
                    .host(host)
                    .port(port)
                    .route(settings.getRoute())
                    .name(settings.getName())
                    .socketAddress(InetSocketAddress.createUnresolved(host, port))
                    .build();

            addMessage(remote, heartBeatMapping);
        }
    }


    private void requestDependency(String requestedRoute)
    {
        val settings = getSettings();
        val bindSocketAddress = settings.getBindAddress();
        val hostAddress = settings.getBindAddress().getAddress();
        val port = settings.getBindAddress().getPort();

        for (val remote : settings.getRemotes())
        {
            val remoteAddress = remote.getAddress();

            final String host = (hostAddress != null)
                    ? networkUtil.getReachableLocalAddress(hostAddress, remoteAddress).getHostAddress()
                    : bindSocketAddress.getHostString();

            val dependencyMapping = Mapping.builder()
                    .host(host)
                    .port(port)
                    .route(settings.getRoute())
                    .name(settings.getName())
                    .dependency(requestedRoute)
                    .socketAddress(InetSocketAddress.createUnresolved(host, port))
                    .build();

            addMessage(remote, dependencyMapping);
        }
    }


    private static final class RouteWaiter
    {
        private final AtomicLong discoveryState = new AtomicLong(0L);

        private volatile long resendNanos = TimeUnit.MILLISECONDS.toNanos(DEPENDENCY_RESEND_MILLIES_MIN);


        RouteWaiter()
        {
        }


        State advanceDiscoveryState(long currentNanos)
        {
            val state = discoveryState.get();
            if (state <= currentNanos - resendNanos)
            {
                if (discoveryState.compareAndSet(state, currentNanos))
                {
                    resendNanos = Math.min(resendNanos * 2, DEPENDENCY_RESEND_MILLIES_MAX);
                    return State.SEND;
                }
            }
            else if (state == Long.MAX_VALUE)
            {
                // discovery complete
                return State.DONE;
            }

            return State.WAIT;
        }


        State getDiscoveryState()
        {
            return discoveryState.get() == Long.MAX_VALUE ? State.DONE : State.WAIT;
        }


        void setDiscoveryComplete()
        {
            discoveryState.set(Long.MAX_VALUE);

            synchronized (this)
            {
                notifyAll();
            }
        }


        enum State
        {
            WAIT,
            DONE,
            SEND
        }
    }


    /**
     * To make Collections.shuffle() a little cheaper we use the simpler xorshift algorithm.
     * <p>
     * This is not supposed to produce thread-safe results.
     */
    private static class SomewhatRandom extends Random
    {
        private volatile long unsafeLast;

        SomewhatRandom()
        {
            super();

            unsafeLast = System.currentTimeMillis();
        }

        @Override
        public int nextInt(int limit)
        {
            unsafeLast ^= (unsafeLast << 21);
            unsafeLast ^= (unsafeLast >>> 35);
            unsafeLast ^= (unsafeLast << 4);

            final int result = (int) unsafeLast % limit;

            return (result < 0) ? -result : result;
        }
    }
}
