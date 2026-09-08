Read these three first

  1 · Grab — "Griffin"

  [https://engineering.grab.com/griffin](https://engineering.grab.com/griffin) 

  What it is: Grab is the Uber of Southeast Asia. This is their  
  engineering team writing about the fraud system they built  
  in-house.

  Why you care: They faced your exact fork in the road — use  
  existing rule engine software, or build our own? They looked at  
  Drools, the industry standard, and decided to build their own  
  instead.

  What to look for while reading:  
  \- Their reasons for saying no to Drools. Two matter: the rule  
    language is hard for non-programmers to write, and Drools  
    assumes your rules stay roughly the same shape over time —  
    theirs didn't.  
  \- The scale they run at. If the numbers are big, that's your  
    defence: "this isn't a shortcut, a company far bigger than this  
    project made the same call."

  The one line to walk away with: "A production fraud team  
  evaluated the off-the-shelf option and built their own. Here's  
  why, and here's why it applies to me."

  —  
Notes : 

- Griffen used shadow mode & 1 % \-\> 100% , Change Approval flow , version control & roll back, RBAC-based permission system, do we do this in our implementation , if not what's the path we take to do  
- Does our system have version of a Data Orchestration ([https://github.com/grab/symphony](https://github.com/grab/symphony)) , if not whats a path to doing so  
- Griffen has a UI that makes a novice able to add and change rules , how do we add and change rules , how could we build a UI for this so that a novice can build and change rules (with some review ofcourse before applying rule or change)  
- In our system whats equivalent to checkpoint , rules, counters, headers, approve , segments , treatments.   
- I need to deeply understand our latency , the rules are useless if they take 1 billion seconds , where is the latency in our system , the rules are in db , so to get a rule is a db fetch , how did we mitigate , make faster and what's the plan to progress this to handle millions in scale.  
- “Use built-in functions https://docs.python.org/3/library/functions.html. It is written in C, no one can beat it.” are we doing something of the sort in our system how does this apply to java

  2 · Databricks — "Payment fraud detection"

  https://www.databricks.com/blog/payment-fraud-detection

  What it is: A company that sells data tooling, writing about how  
  card fraud actually works and how it's detected.

  Why you care: This is where the whole point of your project comes  
  from.

  What to look for:  
  \- Fraud typologies. A "typology" is just a named pattern of  
    criminal behaviour. Like "card testing" — a criminal has a list  
    of stolen card numbers and doesn't know which still work, so  
    they run lots of tiny transactions to find out. Your rules each  
    target a named typology. That's why your rule set looks  
    deliberate instead of random.  
  \- The decay problem. This is the big one. Fraudsters learn your  
    thresholds. Set a limit at R10,000 and they'll spend R9,500.  
    Your rule silently stops working.

  The one line: "Rules decay because fraudsters learn to operate  
  just below static thresholds." Everything you built about  
  changing rules safely — shadow mode, versioning — exists because  
  of that sentence.

  —  
Notes : 

- What are the fraud typologies we cover in our system and which do we miss and what is oth to cover them  
- What is our systems path to Behavioral analytics (typing cadence, mouse movement, scroll behavior, and session timing ) PS do we have some behavior anylytics ? and Machine learning models fraud detection maybe even lets start with an open source LLM  
- “the entire scoring process completing in under 100 milliseconds to avoid impacting checkout conversion.” is our system under 100ms , how did we time ?  
- Do we utilise Device fingerprinting , whats path we can to do so  
- “A layered stack typically includes network-level controls (rate limiting, IP reputation filtering), authentication controls (MFA, device binding), transaction scoring (real-time ML-based risk scoring), and post-authorization monitoring (chargeback tracking, dispute analytics)” which part does our system cover   
- How does the system mitigate and approach False positives, what did we learn  
- “Modern fraud detection addresses this through threshold optimization experiments that test the revenue impact of different risk-score cutoffs against the fraud prevention benefit. Targeted manual-review queues route borderline transactions to human reviewers rather than automatically declining them, preserving genuine transactions while still catching fraud” does our system do any of this   
- How can we measure our KPIs : fraud rate, false positive rate, chargeback rate and chargeback fees, fraud detection rate, and mean time to detection for fraud (the ones that apply ofcourse)

  3 · Red Hat — "Fraud with Decision Manager 7"

  https://developers.redhat.com/blog/2018/07/26/detecting-credit-ca  
  rd-fraud-with-red-hat-decision-manager-7

  What it is: A tutorial showing how to build fraud detection using  
  Drools — the thing you decided against.

  Why you care: You can't credibly reject something you've never  
  looked at. This is you looking at it.

  What to look for:  
  \- The rule syntax. Drools rules are written in their own language  
    called DRL — not Java. It reads roughly rule "name" when  
    \[conditions\] then \[actions\] end, in separate files.  
  \- Ask yourself while reading: would a fraud analyst — not a  
    programmer — write this? Your answer is no, and that's one of  
    your reasons.

  The one line: "I've seen what DRL looks like. It's a separate  
  language in separate files, and it's not something a risk analyst  
  writes."

  —  
Notes : 

- Where in our system is this part “fetch the context of that transaction from a datastore,”

  Then these three (velocity and audit)

  4 · PXP — "Velocity check"

  https://www.pxp.io/payments-glossary/velocity-check

  What it is: A short glossary entry. Quick read.

  Why you care: "Velocity" just means speed — how many  
  transactions, how fast. Three of your eight rules are velocity  
  rules.

  What to look for:  
  \- The window trade-off. How far back do you look? Short window (5  
    minutes) catches a burst attack but misses a slow criminal.  
    Long window (24 hours) catches the slow one but starts flagging  
    genuinely busy customers — someone booking corporate travel,  
    say. There's no right answer, only a trade-off. You will be  
    asked "why 5 minutes?" This is your answer.  
  \- Attempts vs successes. Subtle and worth having. A criminal  
    testing stolen cards generates lots of failed attempts. A busy  
    legitimate customer generates successful ones. If you count  
    attempts instead of successes, you catch fraudsters and stop  
    annoying real customers. You haven't built this — it's on your  
    "what I'd do next" list, and volunteering it unprompted looks  
    good.

  —  
Notes: 

- “A velocity rule specifies an identifier type (card, BIN, IP, email, device ID), an event (authorisation attempt, decline, successful transaction), a count threshold, and a time window. When the count exceeds the threshold within the window, the rule fires, either declining the transaction, flagging it for review, or adding the identifier to a block list.” how does our system use velocity rules and do we have a blocklist if not what is path to this   
- “Attackers adapt to naive velocity rules. A rule blocking a card after 5 attempts in 10 minutes is easily circumvented by distributing attempts across multiple cards, IPs, and time windows. Effective velocity monitoring uses combinations of identifiers, flagging when a single IP submits 50 different cards, or when a single device produces transactions to multiple merchant accounts, to detect distributed attacks that circumvent single-identifier thresholds.” path to improve our velocity rules to cover this   
- “Velocity rules on authorisation attempts per card and per BIN range are the first line of detection.” does our velocity rule cover this  
- “What identifiers should velocity checks monitor?  
- At minimum: card number (or token), BIN range, IP address, and device fingerprint. Email address and billing address are also high-value identifiers for detecting the same actor using multiple cards. More sophisticated setups add session ID, browser fingerprint, and shipping address to the velocity monitoring set.” which do we cover in our system 

  5 · Chargeback Gurus — "Velocity checks"

  https://www.chargebackgurus.com/blog/velocity-checks

  What it is: Same topic, from the merchant's side.

  Why you care: This is where your three verdicts come from instead  
  of two.

  What to look for:  
  \- Tiered response. Don't block everything suspicious. Mildly  
    suspicious → ask the customer to prove it's them. Very  
    suspicious → block.  
  \- Step-up authentication. That's the formal name for "prove it's  
    you" — the one-time PIN or bank-app tap you get on a big online  
    purchase. It's what your REVIEW verdict would trigger in real  
    life.

  The one line: "REVIEW isn't a fudge between yes and no. It's a  
  real third option that triggers step-up authentication in  
  production."

  —

  6 · FinLego — "Fraud and velocity rule design"

  https://finlego.com/blog/fraud-and-velocity-rule-design-for-walle  
  t-and-card-platforms

  What it is: Fraud rule design with attention to the compliance  
  side.

  Why you care: This backs the part of your system that most  
  candidates skip — the audit trail.

  What to look for:  
  \- Why regulators require you to reconstruct a decision after the  
    fact. If someone was blocked, the bank must be able to explain  
    why, possibly a year later.  
  \- The catch that follows: if you're allowed to edit rules, and  
    your stored decisions just say "rule 5 fired", then you can't  
    reconstruct anything — because rule 5 is different now.

  That's exactly why you never edit rules (you add a new version)  
  and why each stored decision records which version fired.

  —

  Finally these two

  7 · Databricks — "Near real-time anomaly detection"

  https://www.databricks.com/blog/near-real-time-anomaly-detection-  
  delta-live-tables-and-databricks-machine-learning

  What it is: The machine-learning approach to the same problem.

  Why you care: You didn't do this, and you need to say why with  
  respect rather than dismissal.

  What to look for:  
  \- How an ML model spots fraud: it learns from past examples  
    rather than following written rules.  
  \- Then ask the two questions that kill it here: Can it explain  
  \- How an ML model spots fraud: it learns from past examples rather  
    than following written rules.  
  \- Then ask the two questions that kill it here: Can it explain itself  
    to a regulator? (No — it gives a score, not a reason.) What would  
    I train it on? (Your own made-up data — so it would only learn the  
    patterns you told your generator to produce. Circular.)

  The one line: "Not instead of rules. The right shape is a model score  
  becoming one input to a rule, so the decision layer stays  
  explainable."

  —   
Notes :  

- Why didnt we take ML Approach ?

  8 · Sparkov dataset

  https://www.kaggle.com/datasets/kartik2112/fraud-detection

  What it is: A public, made-up card-transaction dataset — not a blog.  
  Just look at the column list.

  Why you care: Answers "why does your transaction have these fields?"  
  Because you copied the shape of a dataset built to resemble real card  
  transactions, rather than inventing fields.

  Also worth knowing — the two datasets you rejected:  
  \- ULB (https://www.kaggle.com/mlg-ulb/creditcardfraud) — the famous  
    one. Useless to you: 28 of its 31 columns are scrambled into  
    anonymous numbers called "V1, V2, V3…". You cannot write an  
    explainable rule about V17. It means nothing to a human.  
  \- IEEE-CIS (https://www.kaggle.com/competitions/ieee-fraud-detection)  
    — richer, but you need a Kaggle account to download it. Your  
    project must run for a reviewer without them signing up for  
    anything.

  —  
Notes : 

- Is this the dataset we based our system on and why ???

