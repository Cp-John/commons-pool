/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.pool2;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import junit.framework.TestCase;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.junit.After;
import org.junit.Test;

/**
 * Abstract {@link TestCase} for {@link ObjectPool} implementations.
 * @author Rodney Waldhoff
 * @author Sandy McArthur
 * @version $Revision$ $Date$
 */
public abstract class TestKeyedObjectPool {

    /**
     * Create an <code>KeyedObjectPool</code> with the specified factory.
     * The pool should be in a default configuration and conform to the expected
     * behaviors described in {@link KeyedObjectPool}.
     * Generally speaking there should be no limits on the various object counts.
     */
    protected abstract KeyedObjectPool<Object,Object> makeEmptyPool(KeyedPoolableObjectFactory<Object,Object> factory);

    protected final String KEY = "key";
    
    private KeyedObjectPool<Object,Object> _pool = null;
    
    protected abstract Object makeKey(int n);
    
    protected abstract boolean isFifo();
    
    protected abstract boolean isLifo();
    
    @After
    public void tearDown() throws Exception {
        _pool = null;
    }
    
    /**
     * Create an {@link KeyedObjectPool} instance
     * that can contain at least <i>mincapacity</i>
     * idle and active objects, or
     * throw {@link IllegalArgumentException}
     * if such a pool cannot be created.
     * @param mincapacity 
     */
    protected abstract KeyedObjectPool<Object,Object> makeEmptyPool(int mincapacity);  
    
    /**
     * Return what we expect to be the n<sup>th</sup>
     * object (zero indexed) created by the pool
     * for the given key.
     * @param key 
     * @param n 
     */
    protected abstract Object getNthObject(Object key, int n);  

    @Test
    public void testClosedPoolBehavior() throws Exception {
        final KeyedObjectPool<Object,Object> pool;
        try {
            pool = makeEmptyPool(new BaseKeyedPoolableObjectFactory<Object,Object>() {
                @Override
                public Object makeObject(final Object key) throws Exception {
                    return new Object();
                }
            });
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        }

        Object o1 = pool.borrowObject(KEY);
        Object o2 = pool.borrowObject(KEY);

        pool.close();

        try {
            pool.addObject(KEY);
            fail("A closed pool must throw an IllegalStateException when addObject is called.");
        } catch (IllegalStateException ise) {
            // expected
        }

        try {
            pool.borrowObject(KEY);
            fail("A closed pool must throw an IllegalStateException when borrowObject is called.");
        } catch (IllegalStateException ise) {
            // expected
        }

        // The following should not throw exceptions just because the pool is closed.
        assertEquals("A closed pool shouldn't have any idle objects.", 0, pool.getNumIdle(KEY));
        assertEquals("A closed pool shouldn't have any idle objects.", 0, pool.getNumIdle());
        pool.getNumActive();
        pool.getNumActive(KEY);
        pool.returnObject(KEY, o1);
        assertEquals("returnObject should not add items back into the idle object pool for a closed pool.", 0, pool.getNumIdle(KEY));
        assertEquals("returnObject should not add items back into the idle object pool for a closed pool.", 0, pool.getNumIdle());
        pool.invalidateObject(KEY, o2);
        pool.clear(KEY);
        pool.clear();
        pool.close();
    }

    private final Integer ZERO = new Integer(0);
    private final Integer ONE = new Integer(1);

    @Test
    public void testKPOFAddObjectUsage() throws Exception {
        final FailingKeyedPoolableObjectFactory factory = new FailingKeyedPoolableObjectFactory();
        final KeyedObjectPool<Object,Object> pool;
        try {
            pool = makeEmptyPool(factory);
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        }
        final List<MethodCall> expectedMethods = new ArrayList<MethodCall>();

        // addObject should make a new object, pasivate it and put it in the pool
        pool.addObject(KEY);
        expectedMethods.add(new MethodCall("makeObject", KEY).returned(ZERO));
        expectedMethods.add(new MethodCall("passivateObject", KEY, ZERO));
        assertEquals(expectedMethods, factory.getMethodCalls());

        //// Test exception handling of addObject
        reset(pool, factory, expectedMethods);

        // makeObject Exceptions should be propagated to client code from addObject
        factory.setMakeObjectFail(true);
        try {
            pool.addObject(KEY);
            fail("Expected addObject to propagate makeObject exception.");
        } catch (PrivateException pe) {
            // expected
        }
        expectedMethods.add(new MethodCall("makeObject", KEY));
        assertEquals(expectedMethods, factory.getMethodCalls());

        clear(factory, expectedMethods);

        // passivateObject Exceptions should be propagated to client code from addObject
        factory.setMakeObjectFail(false);
        factory.setPassivateObjectFail(true);
        try {
            pool.addObject(KEY);
            fail("Expected addObject to propagate passivateObject exception.");
        } catch (PrivateException pe) {
            // expected
        }
        expectedMethods.add(new MethodCall("makeObject", KEY).returned(ONE));
        expectedMethods.add(new MethodCall("passivateObject", KEY, ONE));
        assertEquals(expectedMethods, factory.getMethodCalls());
        pool.close();
    }

    @Test
    public void testKPOFBorrowObjectUsages() throws Exception {
        final FailingKeyedPoolableObjectFactory factory = new FailingKeyedPoolableObjectFactory();
        final KeyedObjectPool<Object,Object> pool;
        try {
            pool = makeEmptyPool(factory);
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        }
        final List<MethodCall> expectedMethods = new ArrayList<MethodCall>();
        Object obj;
        
        if (pool instanceof GenericKeyedObjectPool) {
            ((GenericKeyedObjectPool<Object,Object>) pool).setTestOnBorrow(true);
        }

        /// Test correct behavior code paths

        // existing idle object should be activated and validated
        pool.addObject(KEY);
        clear(factory, expectedMethods);
        obj = pool.borrowObject(KEY);
        expectedMethods.add(new MethodCall("activateObject", KEY, ZERO));
        expectedMethods.add(new MethodCall("validateObject", KEY, ZERO).returned(Boolean.TRUE));
        assertEquals(expectedMethods, factory.getMethodCalls());
        pool.returnObject(KEY, obj);

        //// Test exception handling of borrowObject
        reset(pool, factory, expectedMethods);

        // makeObject Exceptions should be propagated to client code from borrowObject
        factory.setMakeObjectFail(true);
        try {
            obj = pool.borrowObject(KEY);
            fail("Expected borrowObject to propagate makeObject exception.");
        } catch (PrivateException pe) {
            // expected
        }
        expectedMethods.add(new MethodCall("makeObject", KEY));
        assertEquals(expectedMethods, factory.getMethodCalls());


        // when activateObject fails in borrowObject, a new object should be borrowed/created
        reset(pool, factory, expectedMethods);
        pool.addObject(KEY);
        clear(factory, expectedMethods);

        factory.setActivateObjectFail(true);
        expectedMethods.add(new MethodCall("activateObject", KEY, obj));
        try {
            obj = pool.borrowObject(KEY); 
            fail("Expecting NoSuchElementException");
        } catch (NoSuchElementException e) {
            //Activate should fail
        }
        // After idle object fails validation, new on is created and activation
        // fails again for the new one.
        expectedMethods.add(new MethodCall("makeObject", KEY).returned(ONE));
        expectedMethods.add(new MethodCall("activateObject", KEY, ONE));
        TestObjectPool.removeDestroyObjectCall(factory.getMethodCalls()); // The exact timing of destroyObject is flexible here.
        assertEquals(expectedMethods, factory.getMethodCalls());

        // when validateObject fails in borrowObject, a new object should be borrowed/created
        reset(pool, factory, expectedMethods);
        pool.addObject(KEY);
        clear(factory, expectedMethods);

        factory.setValidateObjectFail(true);
        // testOnBorrow is on, so this will throw when the newly created instance
        // fails validation
        try {
            obj = pool.borrowObject(KEY);
            fail("Expecting NoSuchElementException");
        } catch (NoSuchElementException ex) {
            // expected
        }
        // Activate, then validate for idle instance
        expectedMethods.add(new MethodCall("activateObject", KEY, ZERO));
        expectedMethods.add(new MethodCall("validateObject", KEY, ZERO));
        // Make new instance, activate succeeds, validate fails
        expectedMethods.add(new MethodCall("makeObject", KEY).returned(ONE));
        expectedMethods.add(new MethodCall("activateObject", KEY, ONE));
        expectedMethods.add(new MethodCall("validateObject", KEY, ONE));
        TestObjectPool.removeDestroyObjectCall(factory.getMethodCalls());
        assertEquals(expectedMethods, factory.getMethodCalls());
        pool.close();
    }

    @Test
    public void testKPOFReturnObjectUsages() throws Exception {
        final FailingKeyedPoolableObjectFactory factory = new FailingKeyedPoolableObjectFactory();
        final KeyedObjectPool<Object,Object> pool;
        try {
            pool = makeEmptyPool(factory);
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        }
        final List<MethodCall> expectedMethods = new ArrayList<MethodCall>();
        Object obj;

        /// Test correct behavior code paths
        obj = pool.borrowObject(KEY);
        clear(factory, expectedMethods);

        // returned object should be passivated
        pool.returnObject(KEY, obj);
        expectedMethods.add(new MethodCall("passivateObject", KEY, obj));
        assertEquals(expectedMethods, factory.getMethodCalls());

        //// Test exception handling of returnObject
        reset(pool, factory, expectedMethods);

        // passivateObject should swallow exceptions and not add the object to the pool
        pool.addObject(KEY);
        pool.addObject(KEY);
        pool.addObject(KEY);
        assertEquals(3, pool.getNumIdle(KEY));
        obj = pool.borrowObject(KEY);
        obj = pool.borrowObject(KEY);
        assertEquals(1, pool.getNumIdle(KEY));
        assertEquals(2, pool.getNumActive(KEY));
        clear(factory, expectedMethods);
        factory.setPassivateObjectFail(true);
        pool.returnObject(KEY, obj);
        expectedMethods.add(new MethodCall("passivateObject", KEY, obj));
        TestObjectPool.removeDestroyObjectCall(factory.getMethodCalls()); // The exact timing of destroyObject is flexible here.
        assertEquals(expectedMethods, factory.getMethodCalls());
        assertEquals(1, pool.getNumIdle(KEY));   // Not added
        assertEquals(1, pool.getNumActive(KEY)); // But not active

        reset(pool, factory, expectedMethods);
        obj = pool.borrowObject(KEY);
        clear(factory, expectedMethods);
        factory.setPassivateObjectFail(true);
        factory.setDestroyObjectFail(true);
        try {
            pool.returnObject(KEY, obj);
            if (!(pool instanceof GenericKeyedObjectPool)) { // ugh, 1.3-compat
                fail("Expecting destroyObject exception to be propagated");
            }
        } catch (PrivateException ex) {
            // Expected
        }
        pool.close();
    }

    @Test
    public void testKPOFInvalidateObjectUsages() throws Exception {
        final FailingKeyedPoolableObjectFactory factory = new FailingKeyedPoolableObjectFactory();
        final KeyedObjectPool<Object,Object> pool;
        try {
            pool = makeEmptyPool(factory);
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        }
        final List<MethodCall> expectedMethods = new ArrayList<MethodCall>();
        Object obj;

        /// Test correct behavior code paths

        obj = pool.borrowObject(KEY);
        clear(factory, expectedMethods);

        // invalidated object should be destroyed
        pool.invalidateObject(KEY, obj);
        expectedMethods.add(new MethodCall("destroyObject", KEY, obj));
        assertEquals(expectedMethods, factory.getMethodCalls());

        //// Test exception handling of invalidateObject
        reset(pool, factory, expectedMethods);
        obj = pool.borrowObject(KEY);
        clear(factory, expectedMethods);
        factory.setDestroyObjectFail(true);
        try {
            pool.invalidateObject(KEY, obj);
            fail("Expecting destroy exception to propagate");
        } catch (PrivateException ex) {
            // Expected
        }
        Thread.sleep(250); // could be defered
        TestObjectPool.removeDestroyObjectCall(factory.getMethodCalls());
        assertEquals(expectedMethods, factory.getMethodCalls());
        pool.close();
    }

    @Test
    public void testKPOFClearUsages() throws Exception {
        final FailingKeyedPoolableObjectFactory factory = new FailingKeyedPoolableObjectFactory();
        final KeyedObjectPool<Object,Object> pool;
        try {
            pool = makeEmptyPool(factory);
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        }
        final List<MethodCall> expectedMethods = new ArrayList<MethodCall>();

        /// Test correct behavior code paths
        PoolUtils.prefill(pool, KEY, 5);
        pool.clear();

        //// Test exception handling clear should swallow destory object failures
        reset(pool, factory, expectedMethods);
        factory.setDestroyObjectFail(true);
        PoolUtils.prefill(pool, KEY, 5);
        pool.clear();
        pool.close();
    }

    @Test
    public void testKPOFCloseUsages() throws Exception {
        final FailingKeyedPoolableObjectFactory factory = new FailingKeyedPoolableObjectFactory();
        KeyedObjectPool<Object,Object> pool;
        try {
            pool = makeEmptyPool(factory);
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        }
        final List<MethodCall> expectedMethods = new ArrayList<MethodCall>();

        /// Test correct behavior code paths
        PoolUtils.prefill(pool, KEY, 5);
        pool.close();


        //// Test exception handling close should swallow failures
        pool = makeEmptyPool(factory);
        reset(pool, factory, expectedMethods);
        factory.setDestroyObjectFail(true);
        PoolUtils.prefill(pool, KEY, 5);
        pool.close();
    }

    @Test
    public void testToString() throws Exception {
        final FailingKeyedPoolableObjectFactory factory =
                new FailingKeyedPoolableObjectFactory();
        KeyedObjectPool<Object,Object> pool = makeEmptyPool(factory);
        try {
            pool.toString();
        } catch(UnsupportedOperationException uoe) {
            return; // test not supported
        } finally {
            pool.close();
        }
    }
    
    @Test
    public void testBaseBorrowReturn() throws Exception {
        try {
            _pool = makeEmptyPool(3);
        } catch(UnsupportedOperationException uoe) {
            return; // skip this test if unsupported
        }
        Object keya = makeKey(0);
        Object obj0 = _pool.borrowObject(keya);
        assertEquals(getNthObject(keya,0),obj0);
        Object obj1 = _pool.borrowObject(keya);
        assertEquals(getNthObject(keya,1),obj1);
        Object obj2 = _pool.borrowObject(keya);
        assertEquals(getNthObject(keya,2),obj2);
        _pool.returnObject(keya,obj2);
        obj2 = _pool.borrowObject(keya);
        assertEquals(getNthObject(keya,2),obj2);
        _pool.returnObject(keya,obj1);
        obj1 = _pool.borrowObject(keya);
        assertEquals(getNthObject(keya,1),obj1);
        _pool.returnObject(keya,obj0);
        _pool.returnObject(keya,obj2);
        obj2 = _pool.borrowObject(keya);
        if (isLifo()) {
            assertEquals(getNthObject(keya,2),obj2);
        }
        if (isFifo()) {
            assertEquals(getNthObject(keya,0),obj2);
        }
        obj0 = _pool.borrowObject(keya);
        if (isLifo()) {
            assertEquals(getNthObject(keya,0),obj0);
        }
        if (isFifo()) {
            assertEquals(getNthObject(keya,2),obj0);
        }
        _pool.close();
    }

    @Test
    public void testBaseBorrow() throws Exception {
        try {
            _pool = makeEmptyPool(3);
        } catch(UnsupportedOperationException uoe) {
            return; // skip this test if unsupported
        }
        Object keya = makeKey(0);
        Object keyb = makeKey(1);
        assertEquals("1",getNthObject(keya,0),_pool.borrowObject(keya));
        assertEquals("2",getNthObject(keyb,0),_pool.borrowObject(keyb));
        assertEquals("3",getNthObject(keyb,1),_pool.borrowObject(keyb));
        assertEquals("4",getNthObject(keya,1),_pool.borrowObject(keya));
        assertEquals("5",getNthObject(keyb,2),_pool.borrowObject(keyb));
        assertEquals("6",getNthObject(keya,2),_pool.borrowObject(keya));
        _pool.close();
    }

    @Test
    public void testBaseNumActiveNumIdle() throws Exception {
        try {
            _pool = makeEmptyPool(3);
        } catch(UnsupportedOperationException uoe) {
            return; // skip this test if unsupported
        }
        Object keya = makeKey(0);
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        Object obj0 = _pool.borrowObject(keya);
        assertEquals(1,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        Object obj1 = _pool.borrowObject(keya);
        assertEquals(2,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        _pool.returnObject(keya,obj1);
        assertEquals(1,_pool.getNumActive(keya));
        assertEquals(1,_pool.getNumIdle(keya));
        _pool.returnObject(keya,obj0);
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(2,_pool.getNumIdle(keya));

        assertEquals(0,_pool.getNumActive("xyzzy12345"));
        assertEquals(0,_pool.getNumIdle("xyzzy12345"));
        
        _pool.close();
    }

    @Test
    public void testBaseNumActiveNumIdle2() throws Exception {
        try {
            _pool = makeEmptyPool(6);
        } catch(UnsupportedOperationException uoe) {
            return; // skip this test if unsupported
        }
        Object keya = makeKey(0);
        Object keyb = makeKey(1);
        assertEquals(0,_pool.getNumActive());
        assertEquals(0,_pool.getNumIdle());
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        assertEquals(0,_pool.getNumActive(keyb));
        assertEquals(0,_pool.getNumIdle(keyb));

        Object objA0 = _pool.borrowObject(keya);
        Object objB0 = _pool.borrowObject(keyb);

        assertEquals(2,_pool.getNumActive());
        assertEquals(0,_pool.getNumIdle());
        assertEquals(1,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        assertEquals(1,_pool.getNumActive(keyb));
        assertEquals(0,_pool.getNumIdle(keyb));

        Object objA1 = _pool.borrowObject(keya);
        Object objB1 = _pool.borrowObject(keyb);

        assertEquals(4,_pool.getNumActive());
        assertEquals(0,_pool.getNumIdle());
        assertEquals(2,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        assertEquals(2,_pool.getNumActive(keyb));
        assertEquals(0,_pool.getNumIdle(keyb));

        _pool.returnObject(keya,objA0);
        _pool.returnObject(keyb,objB0);

        assertEquals(2,_pool.getNumActive());
        assertEquals(2,_pool.getNumIdle());
        assertEquals(1,_pool.getNumActive(keya));
        assertEquals(1,_pool.getNumIdle(keya));
        assertEquals(1,_pool.getNumActive(keyb));
        assertEquals(1,_pool.getNumIdle(keyb));

        _pool.returnObject(keya,objA1);
        _pool.returnObject(keyb,objB1);

        assertEquals(0,_pool.getNumActive());
        assertEquals(4,_pool.getNumIdle());
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(2,_pool.getNumIdle(keya));
        assertEquals(0,_pool.getNumActive(keyb));
        assertEquals(2,_pool.getNumIdle(keyb));
        
        _pool.close();
    }

    @Test
    public void testBaseClear() throws Exception {
        try {
            _pool = makeEmptyPool(3);
        } catch(UnsupportedOperationException uoe) {
            return; // skip this test if unsupported
        }
        Object keya = makeKey(0);
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        Object obj0 = _pool.borrowObject(keya);
        Object obj1 = _pool.borrowObject(keya);
        assertEquals(2,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        _pool.returnObject(keya,obj1);
        _pool.returnObject(keya,obj0);
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(2,_pool.getNumIdle(keya));
        _pool.clear(keya);
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        Object obj2 = _pool.borrowObject(keya);
        assertEquals(getNthObject(keya,2),obj2);
        _pool.close();
    }

    @Test
    public void testBaseInvalidateObject() throws Exception {
        try {
            _pool = makeEmptyPool(3);
        } catch(UnsupportedOperationException uoe) {
            return; // skip this test if unsupported
        }
        Object keya = makeKey(0);
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        Object obj0 = _pool.borrowObject(keya);
        Object obj1 = _pool.borrowObject(keya);
        assertEquals(2,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        _pool.invalidateObject(keya,obj0);
        assertEquals(1,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        _pool.invalidateObject(keya,obj1);
        assertEquals(0,_pool.getNumActive(keya));
        assertEquals(0,_pool.getNumIdle(keya));
        _pool.close();
    }

    @Test
    public void testBaseAddObject() throws Exception {
        try {
            _pool = makeEmptyPool(3);
        } catch(UnsupportedOperationException uoe) {
            return; // skip this test if unsupported
        }
        Object key = makeKey(0);
        try {
            assertEquals(0,_pool.getNumIdle());
            assertEquals(0,_pool.getNumActive());
            assertEquals(0,_pool.getNumIdle(key));
            assertEquals(0,_pool.getNumActive(key));
            _pool.addObject(key);
            assertEquals(1,_pool.getNumIdle());
            assertEquals(0,_pool.getNumActive());
            assertEquals(1,_pool.getNumIdle(key));
            assertEquals(0,_pool.getNumActive(key));
            Object obj = _pool.borrowObject(key);
            assertEquals(getNthObject(key,0),obj);
            assertEquals(0,_pool.getNumIdle());
            assertEquals(1,_pool.getNumActive());
            assertEquals(0,_pool.getNumIdle(key));
            assertEquals(1,_pool.getNumActive(key));
            _pool.returnObject(key,obj);
            assertEquals(1,_pool.getNumIdle());
            assertEquals(0,_pool.getNumActive());
            assertEquals(1,_pool.getNumIdle(key));
            assertEquals(0,_pool.getNumActive(key));
        } catch(UnsupportedOperationException e) {
            return; // skip this test if one of those calls is unsupported
        } finally {
            _pool.close();
        }
    }


    private void reset(final KeyedObjectPool<Object,Object> pool, final FailingKeyedPoolableObjectFactory factory, final List<MethodCall> expectedMethods) throws Exception {
        pool.clear();
        clear(factory, expectedMethods);
        factory.reset();
    }

    private void clear(final FailingKeyedPoolableObjectFactory factory, final List<MethodCall> expectedMethods) {
        factory.getMethodCalls().clear();
        expectedMethods.clear();
    }

    protected static class FailingKeyedPoolableObjectFactory implements KeyedPoolableObjectFactory<Object,Object> {
        private final List<MethodCall> methodCalls = new ArrayList<MethodCall>();
        private int count = 0;
        private boolean makeObjectFail;
        private boolean activateObjectFail;
        private boolean validateObjectFail;
        private boolean passivateObjectFail;
        private boolean destroyObjectFail;

        public FailingKeyedPoolableObjectFactory() {
        }

        public void reset() {
            count = 0;
            getMethodCalls().clear();
            setMakeObjectFail(false);
            setActivateObjectFail(false);
            setValidateObjectFail(false);
            setPassivateObjectFail(false);
            setDestroyObjectFail(false);
        }

        public List<MethodCall> getMethodCalls() {
            return methodCalls;
        }

        public int getCurrentCount() {
            return count;
        }

        public void setCurrentCount(final int count) {
            this.count = count;
        }

        public boolean isMakeObjectFail() {
            return makeObjectFail;
        }

        public void setMakeObjectFail(boolean makeObjectFail) {
            this.makeObjectFail = makeObjectFail;
        }

        public boolean isDestroyObjectFail() {
            return destroyObjectFail;
        }

        public void setDestroyObjectFail(boolean destroyObjectFail) {
            this.destroyObjectFail = destroyObjectFail;
        }

        public boolean isValidateObjectFail() {
            return validateObjectFail;
        }

        public void setValidateObjectFail(boolean validateObjectFail) {
            this.validateObjectFail = validateObjectFail;
        }

        public boolean isActivateObjectFail() {
            return activateObjectFail;
        }

        public void setActivateObjectFail(boolean activateObjectFail) {
            this.activateObjectFail = activateObjectFail;
        }

        public boolean isPassivateObjectFail() {
            return passivateObjectFail;
        }

        public void setPassivateObjectFail(boolean passivateObjectFail) {
            this.passivateObjectFail = passivateObjectFail;
        }

        @Override
        public Object makeObject(final Object key) throws Exception {
            final MethodCall call = new MethodCall("makeObject", key);
            methodCalls.add(call);
            int count = this.count++;
            if (makeObjectFail) {
                throw new PrivateException("makeObject");
            }
            final Integer obj = new Integer(count);
            call.setReturned(obj);
            return obj;
        }

        @Override
        public void activateObject(final Object key, final Object obj) throws Exception {
            methodCalls.add(new MethodCall("activateObject", key, obj));
            if (activateObjectFail) {
                throw new PrivateException("activateObject");
            }
        }

        @Override
        public boolean validateObject(final Object key, final Object obj) {
            final MethodCall call = new MethodCall("validateObject", key, obj);
            methodCalls.add(call);
            if (validateObjectFail) {
                throw new PrivateException("validateObject");
            }
            final boolean r = true;
            call.returned(new Boolean(r));
            return r;
        }

        @Override
        public void passivateObject(final Object key, final Object obj) throws Exception {
            methodCalls.add(new MethodCall("passivateObject", key, obj));
            if (passivateObjectFail) {
                throw new PrivateException("passivateObject");
            }
        }

        @Override
        public void destroyObject(final Object key, final Object obj) throws Exception {
            methodCalls.add(new MethodCall("destroyObject", key, obj));
            if (destroyObjectFail) {
                throw new PrivateException("destroyObject");
            }
        }
    }
}