package dataRepresentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.Timer;


public class Auctioneer extends Thread{
	
	/*
	 * Auctioneer is start by GUI Bidder list Start Button
	 * The process of each round:
	 * 1> Generate all agents' bids, by invoking agent's auctionResponse(), store in request list
	 * 2> Wait until round duration time up.
	 * 3> Collect next round variable from GUI if any
	 * 4> update information for next round
	 * 5> set flag, bidServlet checks this flag, send response.
	 */
	
	ArrayList<Bid> requestedBids;
	AuctionEnvironment environment;
	ArrayList<AuctionContext> auctionLog = new ArrayList<AuctionContext>();
	Timer roundTimer = new Timer();
	
	public volatile boolean nextRoundNotReady = true;
	
	public Auctioneer(AuctionEnvironment e) {
		this.environment = e;
		this.requestedBids = new ArrayList<Bid>();
	}
	
	public void getBid(Bid bid) {
		synchronized(this.requestedBids) {
			this.requestedBids.add(bid);
			System.err.println("get a request current size:"+requestedBids.size()+",bidder list size:"+this.environment.bidderList.getList().size());
		}
		
	}
	
	public boolean removeBid(Bid bid) {
		synchronized(this.requestedBids) {
			this.requestedBids.remove(bid);
			System.out.println("current request list size after remove one:"+requestedBids.size());
			return true;
		}
	}
	
	@Override
	public void run() {
		while (true) {
			/**************Next Round Start**************/
			System.err.println("******Round " + this.environment.context.getRound() + " Start*****Min increment " + this.environment.context.getMinIncrement() + "*****");
			
			//Collect Agent's bid
			for (Bidder bidder: this.environment.bidderList.getList()) {
				if (bidder instanceof Agent) {
					Bid agentBid = ((Agent)bidder).auctionResponse(this.environment.context);
					requestedBids.add(agentBid);
					System.err.println("Agent:"+bidder.getName()+" place a bid:");
					for (AuctionItem item: agentBid.getItemList()) {
						System.out.println("His price:"+item.getPrice()+"for item:"+item.getName());
					}
				}
			}
			
			while(this.environment.context.roundTimeElapse > 0) {
				// Wait until current round time up, or all bidder send their bid
				deliberateDelay(0.2);
				
				synchronized(this.requestedBids) {
					if (this.requestedBids.size() == this.environment.bidderList.getList().size()) {
						break;
					}
				}
			}
			
			// Wait one more seconds, to wait all defaults bids
			deliberateDelay(1);
			
			System.out.println("Processing Bids...");
			processBids();
			System.out.println("next round starting...");
			updateNextRoundContext();
			/***************Next Round Will Start**********************/
			if (this.environment.context.isFinalRound()) {
				System.err.println("Auction End");
				break; // break from while loop, terminate auctioneer
			}
		}
	}
	
	
	private void setNextRoundReady() {
		this.nextRoundNotReady = false;
	}
	
	private void setNextRoundNotReady() {
		this.nextRoundNotReady = true;
	}
	
	public AuctionContext nextRound() {
		
		return this.environment.context;
	}
	
	private void processBids() {
		
		//Xing at 2014.7.31: Process current bids, update auction context;
		//Xing change at 2014.8.2: Process all bids at once.
		if  (this.environment.context.getType() == AuctionContext.AuctionType.SAA) {
			processSAABids();
		} else if (this.environment.context.getType() == AuctionContext.AuctionType.CCA) {
			processCCABids();
		}
		
		synchronized(this.requestedBids) {
			requestedBids.clear();
		}
		//record current round log 
		this.recordLog();

	}
	
	private void processSAABids() {
		boolean newBids = false;
		for (Bid bid: this.requestedBids) {
			for (AuctionItem bidderItem: bid.getItemList()) {
				double originalPrice = fetchItemPrice(bidderItem.getID());

				if (originalPrice < bidderItem.getPrice()) {
					putItemPrice(bid.getBidder(), bidderItem.getID(), bidderItem.getPrice());
					if (!newBids) {
						newBids = true;
					}
				} else if (Math.abs(originalPrice - bidderItem.getPrice()) <= 0.001 && flipCoinWin()) {
					//If this bidder's price is equal to current highest price, and win flip-a-coin
					putItemPrice(bid.getBidder(), bidderItem.getID(), bidderItem.getPrice());
					if (!newBids) {
						newBids = true;
					}
				}
			}
		}
		if (!newBids) {
			this.environment.context.setFinalRound();
		}
	}
	
	private void processCCABids() {
		
		//firstly clear last round temporary owners
		for(AuctionItem item: this.environment.context.getItemList()) {
			if (!item.biddingFinised) {
				item.clearOwners();
			}
		}
		
		//creat a temporary array to story the total number all bidders require each round
		int itemNumber = this.environment.context.getItemList().size();
		int[] thisRoundRequirment = new int[itemNumber];
		for (int i=0; i<itemNumber; i++) {
			thisRoundRequirment[i] = 0;
		}
		
		//collect requirment of each item
		for (Bid bid: this.requestedBids) {
			for (AuctionItem bidderItem: bid.getItemList()) {
				thisRoundRequirment[bidderItem.getID()] += bidderItem.getRequiredQuantity();
				placeItemOwner(bidderItem.getID(), bid.getBidder().getName(), bidderItem.getRequiredQuantity());
				System.out.println("Bidder"+ bid.getBidder().getName()+ " wants itemID:" + bidderItem.getID() + " require " + bidderItem.getRequiredQuantity());
			}
		}
		for (AuctionItem item: this.environment.context.getItemList()) {
			item.setRequiredQuantity(thisRoundRequirment[item.getID()]);
			if (item.getRequiredQuantity() <= item.getQuantity()) {
				//set this item is finish bidding
				item.biddingFinised = true; 
			}
		}
		
	}
	private void updateNextRoundContext() {
		
		
		/*
		 * reset parameter via GUI, e.g. minimun_increment
		 */
		this.environment.context.bidsProcessingFinished = true; //set flag true: GUI can update info
		while (false == this.environment.context.bidsProcessingFinished); //wait till GUI finish update
		
		this.environment.context.incrementRound();
		this.environment.context.roundTimeElapse = this.environment.context.getDurationTime();
		
		//some update for CCA Auction
		if (this.environment.context.getType() == AuctionContext.AuctionType.CCA) 
			updateNextRoundPriceForCCA();
		
		//set flag true, bidservlet can send response
		this.setNextRoundReady();
		
		//make sure every one receive their responses
		while (this.requestedBids.size() > 0) {
			deliberateDelay(0.5);
			//waiting to confirm every html response sent
		}
		
		//set flag false, next round bidservlet wait till ready.
		this.requestedBids.clear();
		this.setNextRoundNotReady();
	}
	private void recordLog() {
		
		this.auctionLog.add(new AuctionContext(this.environment.context));
	}
	
	public ArrayList<AuctionContext> getLog() {
		return this.auctionLog;
	}
	
	
	private void updateNextRoundPriceForCCA() {
		double priceTick = this.environment.context.getPriceTick() + this.environment.context.getMinIncrement();
		this.environment.context.setPriceTick(priceTick);
		ArrayList<AuctionItem> auctionItems = this.environment.context.getItemList();
		for (AuctionItem item: auctionItems) {
			if (!item.biddingFinised) {
				item.setPrice(priceTick);
			}
		}
	}
	
	private double fetchItemPrice(int itemID) {
		for (AuctionItem item: this.environment.context.getItemList()) {
			if (itemID == item.getID()) {
				return item.getPrice();
			}
		}
		return Double.MAX_VALUE;
	}
	
	private void putItemPrice(Bidder bidder, int itemID, double price) {
		for (AuctionItem item: this.environment.context.getItemList()) {
			if (itemID == item.getID()) {
				item.setPrice(price);
				item.setOwner(bidder);
			}
		}
	}
	
	private void placeItemOwner(int itemID, String name, int amount) {
		for (AuctionItem item: this.environment.context.getItemList()) {
			if (itemID == item.getID()) {
				item.placeOwner(name, amount);
			}
		}
	}
	
	static private void deliberateDelay(double sec) {
		try {
			Thread.currentThread();
			Thread.sleep((int)(sec * 1000));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	static private boolean flipCoinWin() {
		Random generator = new Random(System.currentTimeMillis());
		int coin = generator.nextInt(100);
		return coin > 50 ? true : false;
	}

}
