from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, Image, KeepTogether
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.colors import HexColor, white
from reportlab.lib.enums import TA_LEFT
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from pathlib import Path
from xml.sax.saxutils import escape
root=Path.cwd(); out=root/'output/pdf/G-one-Complete-Project-Description.pdf'
pdfmetrics.registerFont(TTFont('UI','C:/Windows/Fonts/segoeui.ttf'))
pdfmetrics.registerFont(TTFont('UIB','C:/Windows/Fonts/segoeuib.ttf'))
pdfmetrics.registerFontFamily('UI',normal='UI',bold='UIB',italic='UI',boldItalic='UIB')
ink=HexColor('#252622'); muted=HexColor('#62645c'); gold=HexColor('#FFD061'); cream=HexColor('#F8F8F3'); line=HexColor('#E1E2D9')
styles={
'body':ParagraphStyle('body',fontName='UI',fontSize=10,leading=15,textColor=ink,spaceAfter=9),
'small':ParagraphStyle('small',fontName='UI',fontSize=8.5,leading=12,textColor=muted,spaceAfter=7),
'h1':ParagraphStyle('h1',fontName='UIB',fontSize=27,leading=32,textColor=ink,spaceAfter=15),
'h2':ParagraphStyle('h2',fontName='UIB',fontSize=13,leading=18,textColor=ink,spaceBefore=10,spaceAfter=7),
'kicker':ParagraphStyle('kicker',fontName='UIB',fontSize=9,leading=12,textColor=muted,spaceAfter=9),
'cell':ParagraphStyle('cell',fontName='UI',fontSize=9,leading=13,textColor=ink),
'quote':ParagraphStyle('quote',fontName='UIB',fontSize=15,leading=22,textColor=ink,spaceAfter=10)}
story=[]
def p(t,kind='body'): story.append(Paragraph(t,styles[kind]))
def h(t):p(t,'h2')
def bullets(items):
 for x in items:p('• '+x)
def table(head,rows,widths=None):
 data=[[Paragraph('<b>'+escape(x)+'</b>',styles['cell']) for x in head]]+[[Paragraph(escape(x),styles['cell']) for x in r] for r in rows]
 t=Table(data,colWidths=widths or [140,359],repeatRows=1,hAlign='LEFT')
 t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),gold),('ROWBACKGROUNDS',(0,1),(-1,-1),[white,cream]),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),10),('RIGHTPADDING',(0,0),(-1,-1),10),('TOPPADDING',(0,0),(-1,-1),9),('BOTTOMPADDING',(0,0),(-1,-1),9),('LINEBELOW',(0,0),(-1,0),0.5,line),('LINEBELOW',(0,-1),(-1,-1),0.5,line)]));story.append(t);story.append(Spacer(1,10))
def page(n,title,deck):
 if story:story.append(PageBreak())
 p(n,'kicker');p(title,'h1');p(deck)
def call(t):
 t=Table([[Paragraph(t,styles['body'])]],colWidths=[499]);t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),cream),('BOX',(0,0),(-1,-1),.7,line),('LEFTPADDING',(0,0),(-1,-1),14),('RIGHTPADDING',(0,0),(-1,-1),14),('TOPPADDING',(0,0),(-1,-1),12),('BOTTOMPADDING',(0,0),(-1,-1),5)]));story.append(t);story.append(Spacer(1,12))
page('PROJECT PROFILE  /  22 SEPTEMBER 2026','G-one','Personal health. Local intelligence. User-controlled sharing.')
story.append(Image(str(root/'Logo.png'),width=190,height=190));story.append(Spacer(1,20))
p('A personal health companion,<br/>from wearable signals to understandable action.','quote')
p('G-one combines a wearable prototype, an Android application and on-device AI to help people understand available physiological readings, notice unusual changes and make selected emergency information accessible when help is needed.')
call('<b>Private. Local. Always.</b><br/>Core health processing and AI inference run locally. Optional online services and cellular emergency messaging have their own connectivity requirements.')
h('Inside this document')
table(['Simple explanation','Technical explanation'],[['02  Purpose and everyday use','05  Signals and detection architecture'],['03  The app and its tools','06  Local AI, documents and data'],['04  Wearable and hardware evolution','07  SOS, NFC and emergency sharing'],['08  Privacy and connectivity','09  Validation, roadmap and ready-to-use pitch']], [249,250])
p('Prepared from the project code, documentation, prototype references and recorded implementation work. Software capability, physical validation and future concepts are distinguished throughout.','small')
page('02  /  SIMPLE EXPLANATION','What G-one does','A wearable collects signals. Your phone organizes them. Local AI helps explain them.')
p('G-one is designed to bring live readings, trends, personal records, wellness check-ins and emergency information into one place. A person should not need to understand raw sensor packets or technical report language to make sense of the information available to them.')
h('The problem it addresses')
p('Health information is often spread across devices, reports and apps. Unusual changes can be missed, medical terminology can be confusing, and cloud-dependent services may be unavailable during a network outage. These challenges are especially relevant during heat waves and other environmental emergencies.')
h('The everyday journey')
table(['Step','What happens'],[['1. Wear and connect','The strap prototype connects to the Android app over Bluetooth Low Energy.'],['2. Start monitoring','Available readings appear with graphs and connection status. Missing readings remain unavailable rather than becoming invented values.'],['3. Review changes','The app stores readings and evaluates explicit rules, recent patterns and available baseline information.'],['4. Understand','The user can view explanations, ask the assistant questions and review records or session reports.'],['5. Respond and share','Alerts provide awareness. Optional SOS can notify a saved contact; selected emergency details can be shared through NFC or QR.']])
h('Who it is intended to help')
p('Intended users include individuals monitoring everyday wellness, caregivers, outdoor workers, older adults and people who need extra awareness during environmental emergencies. These are intended use cases, not proof that the prototype is clinically validated for each population.')
call('<b>In everyday words</b><br/>“G-one helps you keep track of your health, understand your readings and reach someone when a concerning change is detected. The wearable collects the signals, the phone processes them and the assistant explains them in simple language.”')
p('G-one supports health awareness. It does not establish a diagnosis, replace emergency care or guarantee that every dangerous event will be detected.','small')
page('03  /  THE APPLICATION','One app, five main areas','The interface follows G-one’s cream, charcoal and amber visual language, with clear status and severity cues.')
table(['Area','What the user can do'],[['Health','Review available readings, alerts, optional air quality, sleep entries and stress check-ins.'],['Monitor','View live sensor graphs, connection state and monitoring sessions.'],['Trails','Review history, trends, previous alerts and session information.'],['Tools','Use OCR, screenshot explanation, Circle to Search, quizzes, health guides, reminders and the Vault.'],['Assist','Chat with local AI, optionally include personal context, review conversations and use supported voice features.']])
h('Practical tools')
bullets(['<b>OCR:</b> recognize text from a camera photo or selected image; explain, summarize, check printed ranges or convert it into notes.','<b>Screenshot AI:</b> read screenshot text, simplify it, define terms or extract key information. It is primarily a text workflow, not unrestricted image interpretation.','<b>Circle to Search / Circle Learn:</b> select a screen region using Android capture and overlay permissions, then process its recognized text.','<b>PDF tools:</b> extract available text, use OCR when required, and summarize a quick excerpt or process a longer document in parts.','<b>Quiz Generator:</b> create questions from supplied text or images and show interactive answers, explanations and scores when output can be parsed.','<b>Memory Vault:</b> store, search and reopen records and results. Supported result actions include Copy, Share, Save and Ask G-one.'])
h('Wellness and accessibility')
p('The app includes hydration and movement reminders, educational health-myth explanations and a basic balanced-plate guide. Accessibility work includes labelled controls, spoken chart summaries, readable states and larger touch targets. Voice recognition depends on the phone and may use an online service if offline recognition is unavailable.')
p('Sleep and stress are currently user-reported journals. They are explained in detail on page 8.','small')
page('04  /  HARDWARE','From strap to skin-worn patch','The physical prototype and later product concepts represent different stages of development.')
table(['Stage','Architecture and status'],[['Phase 1: strap prototype','Black fabric/elastic strap with separate sensor boards, ESP32-S3 development board, resistors, jumper wires, connectors and contact hardware. This is the physical prototype shown in the reference photographs.'],['Intermediate PCB concepts','Compact board arrangements integrate controller, sensing, interconnect and power sections. The earlier 3D models represent integration and packaging concepts.'],['Final Jeevan / G-one Core','A proposed chest- or arm-worn patch with enclosure, internal electronics, battery and skin-facing sensor interface. Electrical, mechanical and on-body validation remain necessary.']])
h('Phase-1 hardware roles')
table(['Hardware','Role'],[['ESP32-S3','Collects sensor data, runs wearable firmware and transmits readings using BLE. The phone runs the language model.'],['Optical module','Intended for pulse and SpO2 acquisition. Project references mention both MAX30100 and MAX30102; confirm the fitted module and firmware before finalizing the bill of materials.'],['MPU6050 / IMU','Accelerometer and gyroscope signals support movement context and fall-related logic.'],['DS18B20','Contact temperature. A worn skin sensor is not a direct core-body temperature measurement.'],['Muscle sensor / electrodes','Analog biopotential input for EMG-related processing, requiring contact, placement and calibration.'],['Wiring, resistors and strap','Provide interconnection, required pull-ups or conditioning, mechanical support and sensor placement.']])
call('<b>Keep ECG and EMG distinct.</b> The red muscle sensor in the strap does not, by itself, establish validated ECG acquisition. Dedicated ECG circuitry belongs to the later architecture unless separately implemented and demonstrated.')
page('05  /  TECHNICAL EXPLANATION','Signals, rules and alerts','G-one separates detecting an event from explaining an event.')
p('The monitoring engine uses explicit, reviewable rules rather than asking a language model whether a sensor reading is dangerous. It evaluates available signals, durations, movement context and changes over time. Missing inputs cannot support a meaningful rule evaluation.')
call('<b>Monitoring pipeline</b><br/>Wearable reading → validate and store locally → evaluate rules → store event with standard explanation → alert immediately → optionally improve wording with local AI.')
h('Implemented rule categories')
table(['Category','What is assessed'],[['Oxygen','Critical or sustained low SpO2 readings.'],['Heart rate','High or low pulse patterns, with motion or resting context where applicable.'],['Temperature and heat','Elevated body-temperature input, or environmental heat combined with a physiological response. Skin temperature is kept separate.'],['Dehydration risk','A combination of heat, rising heart rate and rising core-temperature input; an indirect risk indicator, not a direct hydration measurement.'],['Respiratory / cardiovascular','Cross-signal patterns such as falling oxygen with rising heart rate; environmental input where provided.'],['Motion','A high-impact event and subsequent immobility; threshold-based rather than a trained fall classifier.'],['Fatigue and baseline','Sustained elevated resting heart rate and departures from the person’s available baseline.'],['Muscle activity','Sustained high EMG while otherwise still, subject to signal quality and calibration.']])
p('The app also calculates internal heat, respiratory and cardiovascular risk scores. These organize findings; they are not clinically validated probabilities of disease.')
h('Reliability by separation')
p('The standard event explanation is stored and delivered before optional model rewriting. Model loading, timeout, failure or rejected output leaves that explanation in place. A separate worker handles rewriting so later samples do not wait for AI inference.')
page('06  /  TECHNICAL EXPLANATION','Local intelligence and records','The ESP32-S3 handles sensing. The Android phone handles the language model and application logic.')
table(['Layer','Implementation'],[['Mobile UI','Kotlin, Jetpack Compose and Material 3 with G-one styling.'],['Wearable transport','ESP32-S3 firmware and Bluetooth Low Energy communication.'],['Local data','Room plus app-private files and preferences where appropriate.'],['Detection','Testable Kotlin rules, rolling history and baseline comparison.'],['AI model','Qwen2.5-1.5B-Instruct in quantized GGUF format.'],['Inference runtime','llama.cpp / ggml through a native JNI bridge.'],['OCR','ML Kit text recognition on the device.'],['Service lifecycle','Android foreground monitoring service and connection management.'],['Emergency web','Next.js API and viewer, Vercel hosting and private Supabase emergency storage.']])
h('Assist and personal context')
p('Assist supports questions, report explanations, saved chats, response cancellation, reading aloud and optional voice conversation. With My data enabled, the app can provide dated readings, recent session reports, alerts and explicitly selected records. The user can inspect what context is being supplied.')
p('Free-form chat is broader than the constrained alert-explanation path. The model is instructed to avoid diagnoses and medication recommendations, but generated answers can still be wrong.')
h('Document integrity and formatting')
p('Extracted text remains available for checking. Cleanup preserves recognized single-character values, symbols and spacing. Supported AI output includes headings, emphasis, lists and aligned tables. Copy and sharing use readable text. Truncation is flagged when only part of the source fits.')
p('Finished tool results can save automatically; stopped or partial results are kept distinguishable and saved only when requested. OCR can still misread a character, so formatting improvements do not establish medical accuracy. The recent APK is about 1.18 GB because it bundles the local model.','small')
page('07  /  EMERGENCY FEATURES','SOS and emergency identity','Notification and information sharing are separate functions with different permissions and connectivity needs.')
h('Automatic SOS')
p('Automatic SOS is opt-in. When enabled with a saved contact and required Android permissions, the current implementation can send SMS after confirmed live wearable anomalies without asking for approval at each event. It includes rule-specific signal checks, durations, grouping and cooldowns. Optional calling is separately controlled.')
bullets(['Simulated readings and historical replay do not send automatic SOS.','The app groups related alerts for approximately 1.5 seconds. Detection also depends on the live aggregation and rule conditions.','Motion-related SOS requires an impact of at least 5 g. This engineering gate reduces sensitivity to small movements but may miss lower-impact falls.','SMS needs a working SIM, messaging service and mobile signal. A successful send callback does not prove the contact read the message.'])
h('Updating NFC and QR emergency information')
p('A passive NFC tag stores a stable, unique HTTPS link. It does not receive continuous internet updates. With online sharing enabled, the wearer’s app uploads a selected emergency snapshot behind that link. Future scans retrieve the latest successful upload; an open page checks again periodically.')
p('Shared fields can include name, age, self-reported blood group, allergies, conditions, medications, implanted devices, an emergency contact and optional dated wearable readings. The public page is view-only. Editing happens in the wearer’s app, which holds a private edit credential.')
call('<b>Save → upload → refresh the same link.</b><br/>After the tag has been written with the wearer’s unique URL, ordinary profile changes do not require rewriting it. The generic /e address alone cannot identify a wearer.')
h('Failure and privacy behavior')
p('If an upload fails, the website retains the previous snapshot with its timestamp. Anyone holding the read link can access the chosen fields. Stopping sharing attempts to remove the online record, but already copied information cannot be recalled. Offline text NFC or QR is a static snapshot and must be rewritten when details change.')
page('08  /  WELLNESS & PRIVACY','Offline-first, with clear boundaries','Routine monitoring and local AI do not require cloud processing. Optional features have explicit dependencies.')
h('Sleep and stress')
p('Sleep entries record bedtime, wake time, awake minutes and perceived quality. Stress is a daily self-reported five-level rating. Recent trends and visual summaries help users reflect on patterns. These are not automatic sleep stages, physiological stress measurements or mental-health diagnoses.')
h('Optional air quality')
p('The user can select a city for Open-Meteo air-quality context without requiring precise GPS. The app displays modeled US AQI, not an on-body air measurement or India’s National AQI. The last successful value survives network failures and restart, with timestamps and stale status. AQI does not trigger SOS.')
table(['Function','Connection requirement'],[['Wearable readings','Bluetooth connection between wearable and phone.'],['Detection and stored history','No internet required.'],['Bundled AI and OCR','Designed for local processing once the required model is available.'],['Fresh city AQI','Internet required; cached information can remain visible.'],['Updating emergency page','Internet required for upload and first retrieval.'],['Automatic SMS / calls','Cellular service and the required permissions.'],['Voice recognition','Depends on the phone; online fallback may be used.']])
h('What privacy means in this project')
p('Routine health analysis runs locally. Optional AQI sends the city query or city-centre coordinates, not health records; network requests also expose an IP address. Online emergency sharing uploads only the selected snapshot with consent. It is inaccurate to promise that nothing ever leaves the phone in every configuration.')
call('<b>Storage distinction:</b> Android app-private storage limits ordinary access, but the current project documentation states that the database is not encrypted at the database layer. Do not describe it as a fully encrypted health database.')
page('09  /  ROADMAP & PRESENTATION','What is proven. What comes next.','Software implementation, device verification and clinical validation are different milestones.')
h('Recorded status')
p('The latest recorded work in this conversation included 590 passing unit tests, a successful lint run and a signed release APK. Those checks establish software evidence, not universal physical-device behavior or clinical accuracy. Camera, screen-capture and permission flows still need device-level verification for the latest changes.')
bullets(['The recorded hardware verification still had unresolved end-to-end optical heart-rate and SpO2 issues.','EMG interpretation needs placement checks and wearer-specific calibration.','Automatic sleep-stage and physiological stress inference are not implemented.','Disaster prediction and a full doctor-facing monitoring platform are not established current capabilities.'])
h('Final Jeevan / G-one Core concept')
p('The intended patch stack includes an outer cover, light guide, gasket, main PCB, battery, support frame, flexible sensor interface, silicone spacer, electrodes and optical interface, adhesive and release liner. Proposed circuitry includes the controller, optical and motion sensing, temperature sensing, ECG/EMG front ends and power management.')
p('The renders communicate intent. Component fit, circuit manufacturability, dimensions, battery runtime, waterproofing and skin-contact comfort remain engineering targets until demonstrated by a fabricated assembly and appropriate testing.')
h('Ready-to-use project description')
p('G-one is a privacy-focused personal health companion that connects a wearable prototype with an Android application and on-device AI. It collects available physiological signals, displays live readings and trends, and uses explicit detection rules to identify potentially concerning changes. A local assistant helps users understand readings, reports and health information without making cloud processing a requirement.')
p('The platform combines monitoring, session reports, OCR, screenshot and document tools, a personal knowledge vault, wellness reminders, sleep and stress journals, optional air-quality context and opt-in emergency assistance. NFC and QR sharing make selected, timestamped emergency information accessible through a stable link.')
call('<b>Presentation opening</b><br/>“G-one senses through a wearable, processes on your phone and explains through local AI. It helps people notice unusual changes, understand health information and share essential details during an emergency. Private. Local. Always.”')
p('<b>Source basis:</b> project README; current app implementation; docs/OPTIONAL-AQI-WELLNESS.md; docs/SOS_BEHAVIOR.md; docs/NFC-EMERGENCY-SHARING.md; supplied prototype and final-concept references; build and test results recorded on 20 September 2026. Older documentation contains superseded status entries; newer feature-specific behavior takes precedence.','small')
def footer(c,d):
 c.saveState();w,h=595.2756,841.8898
 c.setFillColor(gold);c.rect(0,h-8,w,8,fill=1,stroke=0)
 c.setStrokeColor(line);c.line(48,42,w-48,42)
 c.setFont('UI',8);c.setFillColor(muted);c.drawString(48,28,'G-one  /  Complete project description  /  September 2026');c.drawRightString(w-48,28,f'{d.page:02d}')
 c.restoreState()
doc=SimpleDocTemplate(str(out),pagesize=(595.2756,841.8898),rightMargin=48,leftMargin=48,topMargin=40,bottomMargin=56,title='G-one | Complete Project Description',author='G-one',subject='Simple and technical overview of the G-one personal health companion')
doc.build(story,onFirstPage=footer,onLaterPages=footer)
print(out)
