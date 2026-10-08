/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.HostHistoryEvent;
import com.communitylootshare.domain.HostPeriod;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.SettlementSummary;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/** Reusable history browser: host chains and frozen cumulative checkpoints, never recomputed in Swing. */
public final class HistoryView extends JPanel
{
	private final JTree tree = new JTree(new DefaultMutableTreeNode("Party history"));
	private final JTextArea detail = new JTextArea();
	private List<LootshareSession> sessions = Collections.emptyList();

	public HistoryView()
	{
		super(new BorderLayout());
		detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true);
		tree.addTreeSelectionListener(event -> showSelection());
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(detail));
		split.setDividerLocation(310); split.setResizeWeight(0.3); add(split, BorderLayout.CENTER);
	}
	public void render(List<LootshareSession> history)
	{
		String selected = selectedId();
		sessions = new ArrayList<>(history);
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Party history");
		TreePath selection = null;
		for (LootshareSession session : sessions)
		{
			DefaultMutableTreeNode party = new DefaultMutableTreeNode(new Entry("Party " + session.getPartyId(), session, null, null));
			root.add(party);
			if (session.isEarlierHostChainUnknown()) { party.add(new DefaultMutableTreeNode("Earlier host chain unknown")); }
			if (session.isEarlierHistoryOmitted()) { party.add(new DefaultMutableTreeNode("Earlier history omitted (latest 256 events retained)")); }
			if (session.getHistoryEvents().size() < session.getHistoryCommitments().size())
			{
				party.add(new DefaultMutableTreeNode("Some committed history is unavailable"));
			}
			DefaultMutableTreeNode chain = new DefaultMutableTreeNode("Host chain (chronological)"); party.add(chain);
			Map<String, HostPeriod> periods = new LinkedHashMap<>();
			for (HostHistoryEvent event : session.getHistoryEvents())
			{
				HostPeriod period = event.getPeriod();
				HostPeriod existing = periods.get(period.getPeriodId());
				if (existing == null || period.getEndedAt() != null) { periods.put(period.getPeriodId(), period); }
			}
			if (session.getHostPeriod() != null) { periods.put(session.getHostPeriod().getPeriodId(), session.getHostPeriod()); }
			List<HostPeriod> ordered = new ArrayList<>(periods.values());
			ordered.sort(Comparator.comparing(HostPeriod::getStartedAt).thenComparing(HostPeriod::getPeriodId));
			for (HostPeriod period : ordered)
			{
				DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Entry(period.getHostDisplayName() + " — " + period.getStartedAt()
					+ (period.getEndedAt() == null ? " (open)" : ""), session, period, null));
				chain.add(node);
				if (period.getPeriodId().equals(selected)) { selection = new TreePath(node.getPath()); }
			}
			DefaultMutableTreeNode completed = new DefaultMutableTreeNode("Completed host sessions (newest first)"); party.add(completed);
			List<HostHistoryEvent> events = new ArrayList<>(session.getHistoryEvents());
			events.sort(Comparator.comparing(HostHistoryEvent::getOccurredAt).reversed().thenComparing(HostHistoryEvent::getEventId));
			for (HostHistoryEvent event : events)
			{
				if (event.getKind() != HostHistoryEvent.Kind.PERIOD_CLOSED) { continue; }
				DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Entry(event.getPeriod().getHostDisplayName() + " — "
					+ event.getOccurredAt() + (event.isComplete() ? "" : " (incomplete)"), session, event.getPeriod(), event));
				completed.add(node);
				if (event.getEventId().equals(selected)) { selection = new TreePath(node.getPath()); }
			}
		}
		tree.setModel(new DefaultTreeModel(root));
		for (int row = 0; row < tree.getRowCount(); row++) { tree.expandRow(row); }
		if (selection == null && root.getChildCount() > 0) { selection = new TreePath(((DefaultMutableTreeNode) root.getChildAt(0)).getPath()); }
		if (selection != null) { tree.setSelectionPath(selection); }
		else { detail.setText("No stored host history."); }
	}
	private String selectedId()
	{
		Object node = tree.getLastSelectedPathComponent();
		if (!(node instanceof DefaultMutableTreeNode)) { return null; }
		Object value = ((DefaultMutableTreeNode) node).getUserObject();
		return value instanceof Entry ? ((Entry) value).id() : null;
	}
	private void showSelection()
	{
		Object node = tree.getLastSelectedPathComponent();
		if (!(node instanceof DefaultMutableTreeNode)) { return; }
		Object value = ((DefaultMutableTreeNode) node).getUserObject();
		if (!(value instanceof Entry)) { detail.setText(String.valueOf(value)); return; }
		Entry entry = (Entry) value;
		StringBuilder text = new StringBuilder("Party ").append(entry.session.getPartyId()).append("\n");
		if (entry.period == null)
		{
			text.append("Select a host session to inspect its cumulative checkpoint.\n");
		}
		else
		{
			text.append("Host: ").append(entry.period.getHostDisplayName()).append("\nStarted: ")
				.append(entry.period.getStartedAt()).append("\nEnded: ").append(entry.period.getEndedAt() == null ? "Open" : entry.period.getEndedAt()).append("\n");
			HostHistoryEvent closure = entry.event;
			if (closure == null)
			{
				for (HostHistoryEvent event : entry.session.getHistoryEvents())
				{
					if (event.getKind() == HostHistoryEvent.Kind.PERIOD_CLOSED && event.getPeriod().getPeriodId().equals(entry.period.getPeriodId())) { closure = event; }
				}
			}
			if (closure != null)
			{
				text.append("Reason: ").append(closure.getReason()).append("\n")
					.append(closure.isComplete() ? "Complete verified checkpoint" : "INCOMPLETE: unanswered peers or missing committed records").append("\n")
					.append("Late recovery may improve running balances; this saved checkpoint stays unchanged.\n\nEnding settings\n");
				settings(text, closure.getOldSettings()); summary(text, closure.getAfter());
			}
			text.append("\nSettings applications during this host period\n");
			boolean any = false;
			for (HostHistoryEvent event : entry.session.getHistoryEvents())
			{
				if (event.getKind() == HostHistoryEvent.Kind.SETTINGS_APPLIED && event.getPeriod().getPeriodId().equals(entry.period.getPeriodId()))
				{
					any = true;
					text.append("\n").append(event.getOccurredAt()).append(" (revision ").append(event.getRevision()).append(")\nBefore\n");
					settings(text, event.getOldSettings()); summary(text, event.getBefore());
					text.append("After\n"); settings(text, event.getNewSettings()); summary(text, event.getAfter());
				}
			}
			if (!any) { text.append("None recorded.\n"); }
		}
		if (entry.session.isEarlierHostChainUnknown()) { text.append("\nEarlier host chain unknown.\n"); }
		if (entry.session.isEarlierHistoryOmitted()) { text.append("\nEarlier history omitted.\n"); }
		detail.setText(text.toString()); detail.setCaretPosition(0);
	}
	private static void settings(StringBuilder text, LootshareSettings s)
	{
		text.append(String.format(java.util.Locale.US, "Minimum split: %,d gp\n", s.getMinimumSharedLootValue()))
			.append("New loot valuation: ").append(s.getLootValueBasis().getDisplayName()).append("\n")
			.append("Capture NPC/activity/player/pickpocket/other: ").append(s.isCaptureNpcLoot()).append(" / ")
			.append(s.isCaptureEventLoot()).append(" / ").append(s.isCapturePlayerLoot()).append(" / ")
			.append(s.isCapturePickpocketLoot()).append(" / ").append(s.isCaptureUnknownLoot()).append("\n")
			.append("Include logged-out members: ").append(s.isIncludeLoggedOutMembers()).append("\n")
			.append("Member manual GP: ").append(s.isAllowMemberManualGp()).append("\n");
	}
	private static void summary(StringBuilder text, SettlementSummary s)
	{
		text.append(String.format(java.util.Locale.US, "Cumulative checkpoint total: %,d gp\n", s.getTotal()));
		for (LootshareCalculation.Balance b : s.getBalances())
		{
			text.append(String.format(java.util.Locale.US, "%s: received %,d; share %,d; net %+,d gp\n", b.getDisplayName(), b.getReceivedValue(), b.getEntitledValue(), b.getNetValue()));
		}
		text.append("Settlement transfers\n");
		for (LootshareCalculation.Transfer t : s.getTransfers())
		{
			text.append(String.format(java.util.Locale.US, "%s → %s: %,d gp\n", t.getFromDisplayName(), t.getToDisplayName(), t.getAmount()));
		}
		if (s.getTransfers().isEmpty()) { text.append("None\n"); }
	}
	private static final class Entry
	{
		private final String label;
		private final LootshareSession session;
		private final HostPeriod period;
		private final HostHistoryEvent event;
		private Entry(String label, LootshareSession session, HostPeriod period, HostHistoryEvent event)
		{
			this.label = label; this.session = session; this.period = period; this.event = event;
		}
		private String id() { return event != null ? event.getEventId() : period == null ? String.valueOf(session.getPartyId()) : period.getPeriodId(); }
		@Override public String toString() { return label; }
	}
}
