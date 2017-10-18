/**
 * Copyright (c) 2016-2017 BIG IoT Project Consortium and others (see below).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors in alphabetical order
 * (for individual contributions, please refer to git log):
 *
 * - Bosch Software Innovations GmbH
 *     > Denis Kramer
 * - Robert Bosch GmbH
 *     > Stefan Schmid (stefan.schmid@bosch.com)
 * - Siemens AG
 *     > Andreas Ziller (andreas.ziller@siemens.com)
 *
 */
package org.eclipse.bigiot.lib.examples;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.eclipse.bigiot.lib.Consumer;
import org.eclipse.bigiot.lib.examples.types.AlternativeParkingPojo;
import org.eclipse.bigiot.lib.examples.types.MyParkingResultPojo;
import org.eclipse.bigiot.lib.examples.types.MyParkingResultPojoAnnotated;
import org.eclipse.bigiot.lib.exceptions.AccessToNonActivatedOfferingException;
import org.eclipse.bigiot.lib.exceptions.AccessToNonSubscribedOfferingException;
import org.eclipse.bigiot.lib.exceptions.HttpErrorException;
import org.eclipse.bigiot.lib.exceptions.IncompleteOfferingQueryException;
import org.eclipse.bigiot.lib.feed.AccessFeed;
import org.eclipse.bigiot.lib.misc.Helper;
import org.eclipse.bigiot.lib.model.BigIotTypes;
import org.eclipse.bigiot.lib.model.BigIotTypes.LicenseType;
import org.eclipse.bigiot.lib.model.BigIotTypes.PricingModel;
import org.eclipse.bigiot.lib.model.Information;
import org.eclipse.bigiot.lib.model.Price.Euros;
import org.eclipse.bigiot.lib.offering.AccessParameters;
import org.eclipse.bigiot.lib.offering.AccessResponse;
import org.eclipse.bigiot.lib.offering.Offering;
import org.eclipse.bigiot.lib.offering.OfferingSelector;
import org.eclipse.bigiot.lib.offering.SubscribableOfferingDescription;
import org.eclipse.bigiot.lib.offering.mapping.OutputMapping;
import org.eclipse.bigiot.lib.query.OfferingQuery;
import org.eclipse.bigiot.lib.query.elements.RegionFilter;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** Example for using BIG IoT API as a consumer. This example corresponds with ExampleProviderNew.java
 * 
 * 
 */
public class ComplexExampleConsumer {

	private static final String MARKETPLACE_URI = "https://market.big-iot.org";
//	private static final String MARKETPLACE_URI = "https://market-int.big-iot.org";
//	private static final String MARKETPLACE_URI = "https://market-dev.big-iot.org";
	
	private static final String CONSUMER_ID	    = "Null_Island-Parking_App";
	private static final String CONSUMER_SECRET = "-9DLobRfRx63EwL_OJYj-w==";

	final static Logger logger = LoggerFactory.getLogger(ComplexExampleConsumer.class);

	/*
	 * Main Routine
	 */
	public static void log(Object s){
		logger.info(s.toString());
	}

	public static void main(String args[]) throws InterruptedException, ExecutionException, IncompleteOfferingQueryException, IOException, AccessToNonSubscribedOfferingException, AccessToNonActivatedOfferingException, HttpErrorException {

		// Initialize Consumer with Consumer ID and marketplace URL
		Consumer consumer = new Consumer(CONSUMER_ID, MARKETPLACE_URI); 

//		consumer.setProxy("127.0.0.1", 3128); //Enable this line if you are behind a proxy
//		consumer.addProxyBypass("172.17.17.100"); //Enable this line and the addresses for internal hosts
		
		consumer.setProxy("194.145.60.1",9400); //Enable this line if you are behind a proxy
		consumer.addProxyBypass("172.17.17.100"); //Enable this line and the addresses for internal hosts
		
		// Authenticate provider on the marketplace
		consumer.authenticate(CONSUMER_SECRET);

		// Construct Offering search query incrementally
		OfferingQuery query = OfferingQuery
				.create("ParkingQuery")
				.withInformation(new Information("Parking Query", "bigiot:Parking"))
				.inCity("Barcelona")
				.withPricingModel(PricingModel.PER_ACCESS)
				.withMaxPrice(Euros.amount(0.002))             
				.withLicenseType(LicenseType.OPEN_DATA_LICENSE);

		CompletableFuture<SubscribableOfferingDescription> offeringDescriptionFuture  = consumer.discover(query)
				.thenApply(SubscribableOfferingDescription::showOfferingDescriptions)
				.thenApply((l) -> OfferingSelector.create().onlyLocalhost().cheapest().mostPermissive().select(l));
		
		
		SubscribableOfferingDescription offeringDescription= offeringDescriptionFuture.get();		
		if(offeringDescription== null) {
			logger.error("Couldn't find any offering. Are sure that one is registered? It could be expired meanwhile");
			System.exit(1);
		}
		
		// Instantiation of Offering Access objects via subscribe		
		CompletableFuture<Offering> offeringFuture = offeringDescription.subscribe();
		Offering offering = offeringFuture.get();

		// Prepare access parameters

		AccessParameters accessParameters= AccessParameters.create()
				.addNameValue("areaSpecification", AccessParameters.create()
						.addNameValue("geoCoordinates", AccessParameters.create()
								.addNameValue("latitude", 50.22)
								.addNameValue("longitude", 8.11))
						.addNameValue("radius", 777));

		CompletableFuture<AccessResponse> response = offering.accessOneTime(accessParameters);
		
		if(response.get().getBody().contains("error")){
			throw new RuntimeException(response.get().getBody());
		}
		else{			
			log("One time Offering access: " + response.get().asJsonNode().size() + " elements received. ");
		}

		//Mapping the response automatically to your pojo
		List<MyParkingResultPojoAnnotated> parkingResult = response.get().map(MyParkingResultPojoAnnotated.class);
		// Alternatively you can manually map your response
		List parkingResult2 = response.get().map(MyParkingResultPojo.class, OutputMapping.create().addTypeMapping("schema:geoCoordinates", "myCoordinate").addTypeMapping("datex:distanceFromParkingSpace", "myDistance").addTypeMapping("datex:parkingSpaceStatus", "myStatus"));

		//Or you can do your own mapping cherry-picking your favorite fields
		List parkingResult3 = response.get().map(AlternativeParkingPojo.class, OutputMapping.create()
				.addNameMapping("geoCoordinates.latitude", "coordinates.latitude")
				.addNameMapping("geoCoordinates.longitude", "coordinates.longitude")
				.addNameMapping("distance", "meters")
				);

		Thread.sleep(5 * Helper.Second);

		Duration feedDuration = Duration.standardHours(1);

		// Create a data feed using callbacks for the received results		
		AccessFeed accessFeed = offering.accessContinuous(accessParameters, 
				feedDuration.getMillis(), (f,r)->log("Incoming feed data: "+ r.asJsonNode().size() + " elements received. "),(f,r)->log("Feed operation failed"));

		Thread.sleep(23 * Helper.Second);

		// Pausing Feed
		accessFeed.stop();

		// Printing feed status
		log(accessFeed.getStatus());

		Thread.sleep(10 * Helper.Second);

		// Resuming Feed
		accessFeed.resume();

		Thread.sleep(10 * Helper.Second);

		// Setting a new lifetime for the feed
		accessFeed.setLifetimeSeconds(5000);

		Thread.sleep(10 * Helper.Second);
		
		accessFeed.stop();

		// Unsubscribe Offering
		offering.unsubscribe();

		// Terminate consumer session (unsubscribe from marketplace)
		consumer.terminate();

	}
}
