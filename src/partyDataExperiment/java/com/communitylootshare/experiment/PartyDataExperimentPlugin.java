/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.experiment;

import javax.inject.Inject;
import net.runelite.api.Item;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.data.PartyDataService;
import net.runelite.client.party.data.PartyDataType;
import net.runelite.client.party.data.events.PartyDataEvent;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Passive development probe. Inventory updates never enter the loot ledger. */
@PluginDescriptor(name = "Community Lootshare Party Data Experiment", enabledByDefault = false)
public class PartyDataExperimentPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PartyDataExperimentPlugin.class);

	@Inject
	private PartyDataService partyDataService;

	@Override
	protected void startUp()
	{
		partyDataService.register(this, PartyDataType.INVENTORY);
	}

	@Override
	protected void shutDown()
	{
		partyDataService.unregisterAll(this);
	}

	@Subscribe
	public void onPartyDataEvent(PartyDataEvent event)
	{
		if (event.getType() == PartyDataType.INVENTORY && event.getData() instanceof Item[])
		{
			// No member identities, item contents, or quantities in diagnostic logs.
			log.debug("Party Data inventory snapshot received: {} slots", ((Item[]) event.getData()).length);
		}
	}
}
