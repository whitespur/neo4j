/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
package org.neo4j.kernel.impl.scheduler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.concurrent.BinaryLatch;
import org.neo4j.scheduler.JobScheduler.Group;
import org.neo4j.scheduler.JobScheduler.JobHandle;
import org.neo4j.time.FakeClock;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimeBasedTaskSchedulerTest
{
    private FakeClock clock;
    private ThreadPoolManager pools;
    private TimeBasedTaskScheduler scheduler;
    private AtomicInteger counter;
    private Semaphore semaphore;
    private Group group;

    @Before
    public void setUp()
    {
        clock = new FakeClock();
        pools = new ThreadPoolManager( new ThreadGroup( "TestPool" ) );
        scheduler = new TimeBasedTaskScheduler( clock, pools );
        counter = new AtomicInteger();
        semaphore = new Semaphore( 0 );
        group = new Group( "test" );
    }

    @After
    public void tearDown()
    {
        InterruptedException exception = pools.shutDownAll();
        if ( exception != null )
        {
            throw new RuntimeException( "Test was interrupted?", exception );
        }
    }

    private void assertSemaphoreAcquire() throws InterruptedException
    {
        assertTrue( "Semaphore acquire timeout", semaphore.tryAcquire( 10, TimeUnit.SECONDS ) );
    }

    @Test
    public void mustDelayExecution() throws Exception
    {
        JobHandle handle = scheduler.submit( group, counter::incrementAndGet, 100, 0 );
        scheduler.tick();
        assertThat( counter.get(), is( 0 ) );
        clock.forward( 99, TimeUnit.NANOSECONDS );
        scheduler.tick();
        assertThat( counter.get(), is( 0 ) );
        clock.forward( 1, TimeUnit.NANOSECONDS );
        scheduler.tick();
        handle.waitTermination();
        assertThat( counter.get(), is( 1 ) );
    }

    @Test
    public void mustNotRescheduleDelayedTasks() throws Exception
    {
        JobHandle handle = scheduler.submit( group, counter::incrementAndGet, 100, 0 );
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        handle.waitTermination();
        assertThat( counter.get(), is( 1 ) );
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        handle.waitTermination();
        pools.getThreadPool( group ).shutDown();
        assertThat( counter.get(), is( 1 ) );
    }

    @Test
    public void mustRescheduleRecurringTasks() throws Exception
    {
        scheduler.submit( group, semaphore::release, 100, 100 );
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        assertSemaphoreAcquire();
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        assertSemaphoreAcquire();
    }

    @Test
    public void mustNotRescheduleRecurringTasksThatThrows() throws Exception
    {
        Runnable runnable = () ->
        {
            semaphore.release();
            throw new RuntimeException( "boom" );
        };
        JobHandle handle = scheduler.submit( group, runnable, 100, 100 );
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        assertSemaphoreAcquire();
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        try
        {
            handle.waitTermination();
            fail( "waitTermination should have thrown because the task should have failed." );
        }
        catch ( ExecutionException e )
        {
            assertThat( e.getCause().getMessage(), is( "boom" ) );
        }
        assertThat( semaphore.drainPermits(), is( 0 ) );
    }

    @Test
    public void mustNotStartRecurringTasksWherePriorExecutionHasNotYetFinished() throws Exception
    {
        Runnable runnable = () ->
        {
            counter.incrementAndGet();
            semaphore.acquireUninterruptibly();
        };
        scheduler.submit( group, runnable, 100, 100 );
        for ( int i = 0; i < 4; i++ )
        {
            scheduler.tick();
            clock.forward( 100, TimeUnit.NANOSECONDS );
        }
        semaphore.release( Integer.MAX_VALUE );
        pools.getThreadPool( group ).shutDown();
        assertThat( counter.get(), is( 1 ) );
    }

    @Test
    public void longRunningTasksMustNotDelayExecutionOfOtherTasks() throws Exception
    {
        BinaryLatch latch = new BinaryLatch();
        Runnable longRunning = latch::await;
        Runnable shortRunning = semaphore::release;
        scheduler.submit( group, longRunning, 100, 100 );
        scheduler.submit( group, shortRunning, 100, 100 );
        for ( int i = 0; i < 4; i++ )
        {
            clock.forward( 100, TimeUnit.NANOSECONDS );
            scheduler.tick();
            assertSemaphoreAcquire();
        }
        latch.release();
    }

    @Test
    public void delayedTasksMustNotRunIfCancelledFirst()
    {
        List<Boolean> cancelListener = new ArrayList<>();
        JobHandle handle = scheduler.submit( group, counter::incrementAndGet, 100, 0 );
        handle.registerCancelListener( cancelListener::add );
        clock.forward( 90, TimeUnit.NANOSECONDS );
        scheduler.tick();
        handle.cancel( false );
        clock.forward( 10, TimeUnit.NANOSECONDS );
        scheduler.tick();
        pools.getThreadPool( group ).shutDown();
        assertThat( counter.get(), is( 0 ) );
        assertThat( cancelListener, contains( Boolean.FALSE ) );
    }

    @Test
    public void recurringTasksMustStopWhenCancelled() throws InterruptedException
    {
        List<Boolean> cancelListener = new ArrayList<>();
        Runnable recurring = () ->
        {
            counter.incrementAndGet();
            semaphore.release();
        };
        JobHandle handle = scheduler.submit( group, recurring, 100, 100 );
        handle.registerCancelListener( cancelListener::add );
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        assertSemaphoreAcquire();
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        assertSemaphoreAcquire();
        handle.cancel( true );
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        clock.forward( 100, TimeUnit.NANOSECONDS );
        scheduler.tick();
        pools.getThreadPool( group ).shutDown();
        assertThat( counter.get(), is( 2 ) );
        assertThat( cancelListener, contains( Boolean.TRUE ) );
    }
}
