/*****************************************************************
JADE - Java Agent DEvelopment Framework is a framework to develop 
multi-agent systems in compliance with the FIPA specifications.
Copyright (C) 2000 CSELT S.p.A. 

GNU Lesser General Public License

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation, 
version 2.1 of the License. 

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the
Free Software Foundation, Inc., 59 Temple Place - Suite 330,
Boston, MA  02111-1307, USA.
 *****************************************************************/

package ia.travelagency;

import ia.travelagency.ontology.CreditCard;
import ia.travelagency.ontology.Pay;
import ia.travelagency.ontology.TravelAgencyOntology;
import ia.travelagency.ontology.TravelDetail;
import ia.travelagency.ontology.HotelDetail;
import ia.travelagency.ontology.FlightDetail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Vector;

import jade.content.ContentElement;
import jade.content.ContentManager;
import jade.content.abs.AbsConcept;
import jade.content.abs.AbsContentElement;
import jade.content.abs.AbsIRE;
import jade.content.abs.AbsPredicate;
import jade.content.lang.Codec;
import jade.content.lang.Codec.CodecException;
import jade.content.lang.sl.SLCodec;
import jade.content.lang.sl.SLVocabulary;
import jade.content.onto.Ontology;
import jade.content.onto.OntologyException;
import jade.content.onto.UngroundedException;
import jade.content.onto.basic.Action;
import jade.content.onto.basic.Done;
import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import jade.proto.ContractNetInitiator;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import java.util.Iterator;

public class TravelAgent extends Agent {

	private ContentManager manager = (ContentManager) getContentManager();
	// This agent "speaks" the SL language
	private Codec codec = new SLCodec();
	// This agent "knows" the TravelAgencyOntology
	private Ontology ontology = TravelAgencyOntology.getInstance();

	// The travelDetail to search for
	private TravelDetail _travelDetail = new TravelDetail();

	public TravelDetail getTravelDetail() {
		return this._travelDetail;
	}

	// flight and paygateway agnet id
	private AID payGatewayAgents, flightAgents;

	private CustomerUi customerUi;

	public static ArrayList<FlightDetail> flightDetailList = new ArrayList<FlightDetail>();
	public static ArrayList<FlightDetail> retFlightDetailList = new ArrayList<FlightDetail>();
	public static ArrayList<HotelDetail> hotelDetailList = new ArrayList<HotelDetail>();
        public static String PaymentStatus = null;

	private HotelContractNet hotelContractNet;
	private FlightRequestPerformer flightRequestPerformer;
	private PayGatewayRequestSellBehaviour payRequestPerformer;

	// Put agent initializations here
	protected void setup() {
		// Printout a welcome message
		System.out.println("Agent " + getAID().getName() + " is ready.");

		manager.registerLanguage(codec);
		manager.registerOntology(ontology);

		// customerGui = new CustomerGui(this);
		// customerGui.showGui();
		CustomerUi customerUi = new CustomerUi(this);
		customerUi.setVisible(true);
	}

	/**
	 * This is invoked by the GUI when the user searches for the trip details
	 * The method request quotes from all hotel agents
	 */
	public void doHotelSearch(final TravelDetail travelDetails) {
		_travelDetail = travelDetails;
		hotelDetailList = new ArrayList<HotelDetail>();
		addBehaviour(new OneShotBehaviour(this) {
			@Override
			public void action() {
				ACLMessage cfp = new ACLMessage(ACLMessage.CFP);

				// Update the list of seller agents
				DFAgentDescription template = new DFAgentDescription();
				ServiceDescription sd = new ServiceDescription();
				sd.setType("hotel-agent");
				template.addServices(sd);
				try {
					DFAgentDescription[] hotelAgents = DFService.search(
							myAgent, template);
					System.out.println("Found the following hotel agents:");

					for (int i = 0; i < hotelAgents.length; ++i) {
						cfp.addReceiver(hotelAgents[i].getName());
					}
					// fill the fields of the cfp message
					cfp.setOntology(ontology.getName());
					cfp.setLanguage(codec.getName());
					cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);

					// We want to receive a reply in 10 secs
					cfp.setReplyByDate(new Date(
							System.currentTimeMillis() + 10000));

					fillTravelDetail(cfp, _travelDetail);
					cfp.setConversationId("travel-hotel-agency");
					// Unique value
					cfp.setReplyWith("cfp" + System.currentTimeMillis());
					//myAgent.send(cfp);
				} catch (FIPAException fe) {
					fe.printStackTrace();
				} catch (Exception e) {
					e.printStackTrace();
				}
				// Perform the request
				hotelContractNet = new HotelContractNet(myAgent, cfp, _travelDetail);
				myAgent.addBehaviour(hotelContractNet);

			}
		});
		
	}

	/**
	 * This is invoked by the GUI when the user searches for the trip details
	 * the method request flight agent for available quotes
	 */
	public void doFlightSearch(final TravelDetail travelDetail) {
		_travelDetail = travelDetail;
		// add behaviour that retrieves flight quotes
		addBehaviour(new OneShotBehaviour(this) {
			@Override
			public void action() {
				// Update the list of seller agents
				DFAgentDescription template = new DFAgentDescription();
				ServiceDescription sd = new ServiceDescription();
				sd.setType("flight-agent");
				template.addServices(sd);
				try {
					DFAgentDescription[] result = DFService.search(myAgent,
							template);
					System.out.println("Found the following flight agents:");
					flightAgents = result[0].getName();
					System.out.println(flightAgents.getName());
				} catch (FIPAException fe) {
					fe.printStackTrace();
				}

				// Perform the request
				flightRequestPerformer = new FlightRequestPerformer();
				myAgent.addBehaviour(flightRequestPerformer);
			}
		});
	}

	/**
	 * makes payment and completes transaction
	 */
	public void makePaymentAndFinalize(CreditCard creditCard, int flightIndex,
			int retFlightIndex) {
		addBehaviour(new HandleInformBehaviour(this));
		_travelDetail.setFlightDetail(flightDetailList.get(flightIndex));
		_travelDetail.setRetFlightDetail(retFlightDetailList
				.get(retFlightIndex));
		payRequestPerformer = new PayGatewayRequestSellBehaviour(this,
				_travelDetail, creditCard);
		// retrieve the detail about the payment gateway agent
		addBehaviour(new OneShotBehaviour(this) {
			@Override
			public void action() {
				// Update the list of payment gateway - even though there is
				// only one.
				DFAgentDescription template = new DFAgentDescription();
				ServiceDescription sd = new ServiceDescription();
				sd.setType("paygateway-agent");
				template.addServices(sd);
				try {
					DFAgentDescription[] result = DFService.search(myAgent,
							template);
					System.out
							.println("Found the following paygateway agents:");
					payGatewayAgents = result[0].getName();
					System.out.println(payGatewayAgents.getName());
				} catch (FIPAException fe) {
					fe.printStackTrace();
				}

				// Perform the request
				myAgent.addBehaviour(payRequestPerformer);
			}
		});
	}

	public int calculateAmount(TravelDetail travelDetail, int flightIndex,
			int retFlightIndex) {

		HotelDetail hotelDetail = travelDetail.getHotelDetail();
		int hotelCost = hotelDetail.getHotelCost();
		FlightDetail flightDetail = flightDetailList.get(flightIndex);

		int flightCost = flightDetail.getFlightCost();

		FlightDetail retFlightDetail = retFlightDetailList.get(retFlightIndex);

                int retFlightCost = retFlightDetail.getFlightCost();


		int totalCost = (hotelCost + flightCost + retFlightCost) * travelDetail.getGuestCount();

		return totalCost;
	}

	// Travel-agent handles information when received from user
	class HandleInformBehaviour extends CyclicBehaviour {

		public HandleInformBehaviour(Agent a) {
			super(a);
		}

		public void action() {
			ACLMessage msg = receive(MessageTemplate
					.MatchPerformative(ACLMessage.INFORM));
			if (msg != null) {
				try {
					ContentElement ce = manager.extractContent(msg);
					if (ce instanceof Done) {
                                            removeBehaviour(this);
                                            Done d = (Done) ce;
                                            Action aa = (Action) d.getAction();
                                            Pay pay = (Pay) aa.getAction();


                                            String status = pay.getStatus();

                                            System.out.println("Payment Message -" + status);

                                            if(status.equals(CreditCard.SUCCESS)){
                                                System.out.println("Ok! Payment successfull - for tour package : "
																+ _travelDetail);
												addBehaviour(new FlightConfirmPerformer(_travelDetail));
												hotelContractNet.reset();
												//hotelContractNet.acceptProposal();
                                            } else {
												System.out.println("Payment failure");
                                            }
                                            PaymentStatus = status;
					} else {
						System.out.println("Unknown predicate "
								+ ce.getClass().getName());
					}
				} catch (Exception e) {
					System.out
							.println("Unknown message format - can be ignored: "
									+ e.getMessage());
				}
			} 
			else {
				block();
			}
		}
	}

	/**
	 * Inner class HotelRequestPerformer. This is the behaviour used by
	 * travel-agent to request hotel agents for hotel availability and
	 * reservation
	 */
	class HotelContractNet extends ContractNetInitiator {
		private int nResponders;
		private TravelDetail travelDetail;

		public HotelContractNet(Agent a, ACLMessage cfp,
				TravelDetail travelDetail) {
			super(a, cfp);
			this.travelDetail = travelDetail;
		}
		
		protected void handleNotUnderstood(ACLMessage msg) {
		    System.err.println("!!! ContractNetInitiator handleNotUnderstood: "+msg.toString());
		}
		  
		protected void handleOutOfSequence(ACLMessage msg) {
		    System.err.println("!!! ContractNetInitiator handleOutOfSequence: "+msg.toString());
		}

		protected void handlePropose(ACLMessage propose, Vector v) {
			System.out.println("Agent " + propose.getSender().getName()
					+ " proposed");
		}

		protected void handleRefuse(ACLMessage refuse) {
			System.out.println("Attempt refused: not hotels available = "
					+ refuse.getSender().getName());
		}

		protected void handleFailure(ACLMessage failure) {
			if (failure.getSender().equals(myAgent.getAMS())) {
				// FAILURE notification from the JADE runtime: the receiver
				// does not exist
				System.out.println("Responder does not exist");
			} else {
				System.out.println("Agent " + failure.getSender().getName()
						+ " failed");
			}
			// Immediate failure --> we will not receive a response from this
			// agent
			nResponders--;
		}

		ACLMessage acceptProposalMsg = null;
		protected void handleAllResponses(Vector responses, Vector acceptances) {
			if (responses.size() < nResponders) {
				// Some responder didn't reply within the specified timeout
				System.out.println("Timeout expired: missing "
						+ (nResponders - responses.size()) + " responses");
			}

			// Evaluate proposals.
			HotelDetail bestHotelQuote = null;
			AID bestHotelAgentID = null; // The agent who provides the best
											// offer
			int bestPrice = 0; // The best offered price
			boolean firstItem = true;
			Enumeration e = responses.elements();
			while (e.hasMoreElements()) {
				ACLMessage msg = (ACLMessage) e.nextElement();
				System.out.println("Message received - " + msg);
				if (msg.getPerformative() == ACLMessage.PROPOSE) {

					ACLMessage reply = msg.createReply();
					reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
					acceptances.addElement(reply);

					try {
						HotelDetail hotelDetail = extractHotelDetail(msg);
						int price = hotelDetail.getHotelCost();
						hotelDetailList.add(hotelDetail);
						if (price < bestPrice || firstItem) {
							// This is the best offer at present - will set for
							// first item
							firstItem = false;
							bestHotelQuote = hotelDetail;
							bestPrice = price;
							bestHotelAgentID = reply.getSender();
							acceptProposalMsg = reply;
						}
					} catch (Exception ex) {
					}
				}
			}
			// Accept the proposal of the best proposer
			if (acceptProposalMsg != null) {
				bestHotelQuote.setHostAgentID(bestHotelAgentID);
				travelDetail.setHotelDetail(bestHotelQuote);
				block();
				acceptProposal();
			}
		}

		public void acceptProposal(){
				System.out.println("Accepting proposal " + travelDetail.getHotelDetail());
				acceptProposalMsg.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
				acceptProposalMsg.setConversationId("travel-hotel-agency");
				acceptProposalMsg.setReplyWith("hotel-order" + System.currentTimeMillis());
				try {
					fillTravelDetail(acceptProposalMsg, travelDetail);
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
		}

		protected void handleInform(ACLMessage inform) {
			// Purchase successful. We can terminate
			System.out.println("Hotel best quote : "
					+ travelDetail.getHotelDetail());
		}
	} // End of inner class HotelContractInitiator

	/**
	 * Inner class FlightRequestPerformer. This is the behaviour used by
	 * travel-agent to request flight agents for flight availability and
	 * reservation
	 */
	class FlightRequestPerformer extends Behaviour {
		private MessageTemplate mt; // The template to receive replies
		private int step = 0;
		private AID bestFlightAgentID; // The agent who provides the best offer
		private ArrayList<ArrayList<FlightDetail>> flightDetails = new ArrayList<ArrayList<FlightDetail>>();

		public void action() {
			switch (step) {
			case 0:
				try {
					// Send the cfp to flight-agent
					ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
					cfp.addReceiver(flightAgents);
					cfp.setContentObject(_travelDetail);
					cfp.setConversationId("travel-flight-agency");
					// Unique value
					cfp.setReplyWith("cfp" + System.currentTimeMillis());
					myAgent.send(cfp);
					// Prepare the template to get proposals
					mt = MessageTemplate.and(MessageTemplate
							.MatchConversationId("travel-flight-agency"),
							MessageTemplate.MatchInReplyTo(cfp.getReplyWith()));
				} catch (IOException e) {
					e.printStackTrace();
				}
				step = 1;
				break;
			case 1:
				// Receive all proposals/refusals from seller agents
				ACLMessage reply = myAgent.receive(mt);
				if (reply != null) {
					// Reply received
					if (reply.getPerformative() == ACLMessage.PROPOSE) {
						// This is an offer
						try {
							flightDetails = (ArrayList<ArrayList<FlightDetail>>) reply
									.getContentObject();
							bestFlightAgentID = reply.getSender();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
					flightDetailList = flightDetails.get(0);
					retFlightDetailList = flightDetails.get(1);
					step = 2;
				}
                                else{
                                    block();
                                }
				break;
			}
		}

		public boolean done() {
			if (step == 2 && bestFlightAgentID == null) {
				System.out.println("Attempt failed: Unable to book flight");
			}
			return (step == 2);
		}
	}

	/**
	 * Inner class FlightConfirmPerformer. This is the behaviour used by
	 * travel-agent to confirm flight booking
	 */
	class FlightConfirmPerformer extends Behaviour {
		private MessageTemplate mt; // The template to receive replies
		private int step = 0;
		private TravelDetail travelDetail;

		FlightConfirmPerformer(TravelDetail travelDetail) {
			this.travelDetail = travelDetail;
		}

		public void action() {
			switch (step) {
			case 0:
				try {

					System.out.println("Init flight booking");

					// Once purchase order is successful then proceed flight
					// selection
					ACLMessage order = new ACLMessage(
							ACLMessage.ACCEPT_PROPOSAL);
					FlightDetail flightDetail = travelDetail.getFlightDetail();
					order.addReceiver(flightAgents);
					order.setConversationId("travel-flight-agency");
					order.setReplyWith("order-flight"
							+ System.currentTimeMillis());
					order.setContentObject(travelDetail);

					myAgent.send(order);
					// Prepare the template to get the purchase order reply
					mt = MessageTemplate.and(MessageTemplate
							.MatchConversationId("travel-flight-agency"),
							MessageTemplate
									.MatchInReplyTo(order.getReplyWith()));
				} catch (IOException e) {
					e.printStackTrace();
				}
				step = 1;
				break;
			case 1:
				// Receive flight purchase order from flight agent
				ACLMessage reply = myAgent.receive(mt);
				if (reply != null) {
					// Purchase order reply received
					if (reply.getPerformative() == ACLMessage.INFORM) {
						System.out.println("Flight booked");
					} else {
						System.out
								.println("Attempt failed: requested flight already sold.");
					}
					step = 2;
				}
				break;
			}
		}

		public boolean done() {
			return (step == 2);
		}
	}

	// Agent requests payment gateway to debit user credit card.
	class PayGatewayRequestSellBehaviour extends OneShotBehaviour {

		private TravelDetail travelDetail;
		private CreditCard creditCard;

		public PayGatewayRequestSellBehaviour(Agent a,
				TravelDetail travelDetail, CreditCard creditCard) {
			super(a);
			this.travelDetail = travelDetail;
			this.creditCard = creditCard;
		}

		public void action() {
			System.out.println("Initiating payment transaction");

			// Prepare the message
			ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
			AID receiver = payGatewayAgents;

			msg.setSender(getAID());
			msg.addReceiver(receiver);
			msg.setLanguage(codec.getName());
			msg.setOntology(ontology.getName());

			// Fill the content
			Pay pay = new Pay();
			pay.setBuyer(getAID());
			travelDetail.calculateAmount();
			pay.setTravelDetail(travelDetail);
			pay.setCreditCard(creditCard);

			// SL requires actions to be included into the ACTION construct
			Action a = new Action(getAID(), pay);
			try {
				manager.fillContent(msg, a);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			send(msg);
		}

	}
	
	/**
	 * this method extract a hoteldetail data structure from a message
	 **/
	public HotelDetail extractHotelDetail(ACLMessage msg) throws FIPAException {
		try {
			ContentElement l = getContentManager().extractContent(msg);
			Action a = (Action) l;
			return (HotelDetail) a.getAction();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * this method fills an aclmessage with traveldetails
	 **/
	void fillTravelDetail(ACLMessage msg, TravelDetail travelDetail)
			throws FIPAException {
		Action a = new Action();
		a.setActor(getAID());
		a.setAction(travelDetail);
		try {
			getContentManager().fillContent(msg, a);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
