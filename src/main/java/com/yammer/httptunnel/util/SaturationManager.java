/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.yammer.httptunnel.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.yammer.httptunnel.state.SaturationStateChange;

/**
 * This class is used to monitor the amount of data that has yet to be pushed to
 * the underlying socket, in order to implement the "high/low water mark"
 * facility that controls Channel.isWritable() and the interest ops of http
 * tunnels.
 *
 * @author The Netty Project (netty-dev@lists.jboss.org)
 * @author Iain McGinniss (iain.mcginniss@onedrum.com)
 * @author Jamie Furness (jamie@onedrum.com)
 * @author OneDrum Ltd.
 */
public class SaturationManager {
	private final AtomicLong desaturationPoint;
	private final AtomicLong saturationPoint;
	private final AtomicLong queueSize;
	private final AtomicBoolean saturated;

	public SaturationManager(long desaturationPoint, long saturationPoint) {
		this.desaturationPoint = new AtomicLong(desaturationPoint);
		this.saturationPoint = new AtomicLong(saturationPoint);

		queueSize = new AtomicLong(0);
		saturated = new AtomicBoolean(false);
	}

	public SaturationStateChange queueSizeChanged(long sizeDelta) {
		long newQueueSize = queueSize.addAndGet(sizeDelta);
		if (newQueueSize <= desaturationPoint.get()) {
			if (saturated.compareAndSet(true, false))
				return SaturationStateChange.DESATURATED;

		}
		else if (newQueueSize > saturationPoint.get()) {
			if (saturated.compareAndSet(false, true))
				return SaturationStateChange.SATURATED;
		}

		return SaturationStateChange.NO_CHANGE;
	}

	public void updateThresholds(long desaturationPoint, long saturationPoint) {
		this.desaturationPoint.set(desaturationPoint);
		this.saturationPoint.set(saturationPoint);
	}
}
