# Saved2Serve: 10-minute video script

**Suggested title:** *Saved2Serve: From Project README to a Live AWS Demo*  
**Pacing:** Read at a steady, conversational pace. Bracketed text is an on-screen cue, not narration.

## 0:00–0:45 — Introduction

**[Show the public Saved2Serve website.]**

“Hi, and welcome. In this video, I’ll introduce Saved2Serve, explain what the application is designed to do, and demonstrate the parts that are currently working in the live demo.

Saved2Serve is intended to help people find a starting point for community support. I’ll show the church search, submit a fictional interest registration, and explain how the demo connects the website to services such as EC2 and DynamoDB.

I’ll also point out an important distinction: the live demonstration is one working deployment path, while the repository contains a broader Java and AWS architecture that is not all deployed as part of this demo.”

## 0:45–1:50 — Start with the README

**[Open the project’s README and scroll through its opening sections.]**

“I’ll begin with the [README](../README.md), because it explains the project’s purpose and design decisions.

A central idea is privacy by design. The project aims to use a general area or postcode rather than a full home address or precise GPS location. It also treats consent as important: people should understand what they’re submitting, and optional follow-up contact is a separate choice.

The README is also a map of the project’s development. It describes the website, submission flows, data storage, security and planned or implemented AWS components. For this demonstration, I’ll focus on the features that are connected to the EC2 site I’m showing, rather than suggesting that every feature described in the README is currently running.”

## 1:50–2:50 — Explain what is live

**[Show the README’s EC2 demo section, then the deployment documentation.]**

“The live demo is hosted on an EC2 instance. It serves the website and runs a small API for the demonstration. The interest-registration API saves fictional submissions to a DynamoDB table, and the church search calls Google Places.

This is different from the broader Java 21 and AWS SAM architecture described elsewhere in the README. That architecture includes components such as Lambda functions, encrypted DynamoDB storage, Cognito, and queues for follow-up processing. Those design and code artifacts are part of the repository, but they should not be confused with services that are already deployed in this particular EC2 demonstration.

That distinction matters in a project presentation: I want to show what is actually working, and identify the wider architecture as the project’s broader foundation.”

## 2:50–4:20 — Demonstrate church search

**[On the public site, scroll to “Find a church in your area.” Enter `2001` and click Search.]**

“Here is the church discovery feature. I’ll enter the postcode 2001 and search.

The site sends the area to its backend, which requests nearby church results from Google Places. The browser does not need the Google API key; the key is kept on the EC2 server. The page displays results with names, addresses, and links that open in Google Maps.

One limitation to be clear about is that this is a text search for churches near the area. Results are best-effort: they may not be exhaustive, and the search does not guarantee a precise distance from the postcode. Church details can also change, so visitors should confirm information before travelling.

This demonstrates the basic user flow: enter an area, request results, and follow a result to Google Maps.”

## 4:20–5:35 — Submit a fictional interest registration

**[Scroll to the interest form. Enter clearly fictional details. Do not use real personal information.]**

“Next, I’ll demonstrate the interest form. For a demo, I use fictional details only.

The form asks for a name, email address, and general area or postcode. A phone number is optional. The required consent allows the service to process the registration. The separate follow-up checkbox is optional, so someone can register without agreeing to email follow-up.

I’ll submit this fictional registration now.”

**[Submit and show the confirmation message.]**

“The confirmation tells us the demo saved the registration. This is more than a visual form: the browser sends a request to the API, and the API attempts to write the accepted registration to DynamoDB.”

## 5:35–6:20 — Verify the saved record

**[If prepared, show DynamoDB in the AWS console. Use only a fictional record and avoid exposing credentials or real personal data.]**

“To verify persistence, I can open the demo’s DynamoDB table and look for the test record. This check is useful because it confirms the data reached the storage service, rather than only showing a success message in the browser.

For a presentation recording, keep this screen limited to fictional test data. Don’t expose AWS credentials, the Google API key, or anyone’s real personal information.”

## 6:20–7:35 — Explain the request path and deployment files

**[Show a simple diagram, or the relevant project files.]**

“At a high level, the request path is: the browser sends an API request, Nginx routes it to the demo API, and the API handles the request. For registrations, the API uses the EC2 instance’s AWS permissions to write to DynamoDB. For church searches, it calls Google Places from the server.

The [EC2 user-data script](../infra/ec2-user-data.sh) is useful to understand because it installs Nginx and copies the website files onto the instance. The separate [EC2 API installer](../infra/install-ec2-demo-interest-api.sh) sets up the API service and its Nginx routes. In other words, one part deploys the website, and the other configures the demonstration API.

The Google key is provided to the server through its environment configuration; it should not be placed in the website’s JavaScript or shared publicly.”

## 7:35–8:35 — What else is in the repository?

**[Return to the README and briefly show the Java, AWS, and testing sections.]**

“Beyond this live demo, the repository contains a Java 21 Maven application and a broader AWS SAM template. The README describes features such as consent-aware submission handling, approved-content workflows, leader authorization, and follow-up processing.

Some of these are implemented or documented in the codebase, but deploying them requires the relevant AWS configuration and resources. The current EC2 demonstration is not proof that every part of that larger architecture is live.

The project also includes automated tests. The frontend and EC2 API tests check important behaviors, while the Java project has its Maven test and build commands. Tests help us catch problems consistently; the live demo then shows how a user experiences selected features.”

## 8:35–9:25 — Privacy, limits, and responsible use

**[Show the privacy note on the site and, if useful, the search-result disclaimer.]**

“Privacy and transparency are important parts of this project. The form asks for a general area rather than a full residential address or precise coordinates. Required consent is distinct from optional follow-up consent, and the demo is clearly marked as a demo.

There are also practical limitations. Google Places usage may incur charges, so the API key must be restricted and usage monitored. Search results can be incomplete or out of date. And because this registration flow is a demonstration, I use fictional data rather than real personal details.

These limits are part of describing a working student project responsibly: we should show what it does, explain what it does not guarantee, and avoid overstating its readiness for production.”

## 9:25–10:00 — Wrap-up

**[Return to the homepage or show the project title.]**

“To recap, Saved2Serve combines a privacy-focused community-support concept with a working web demonstration. We saw a Google-powered church search, submitted a fictional interest registration, and discussed how the EC2 API connects to DynamoDB.

We also looked at the README and separated the currently demonstrated EC2 deployment from the project’s broader Java and AWS architecture.

The main next steps for a project like this are to continue testing, configure any additional AWS services that are needed, monitor external API costs, and gather feedback from users. Thank you for watching.”

## Optional video description

An introduction to Saved2Serve, a community-support application focused on privacy-conscious area-based connections. This walkthrough starts with the project README, demonstrates the EC2-hosted church search and fictional interest-registration flow, and explains how the demo connects to Google Places and DynamoDB. It also distinguishes the live EC2 demonstration from the broader Java and AWS architecture described in the repository. Demo only: no real personal information is used; Google Places results are best-effort and may not be exhaustive or within a set distance.
