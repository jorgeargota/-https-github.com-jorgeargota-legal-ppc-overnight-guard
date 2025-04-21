/**
 * Overnight Spend‑Guard for Legal PPC
 * v1.4  – 2025‑04‑21  – Security‑hardened, error‑aware
 * MIT‑licensed – see GitHub repo for tests & changelog.
 */
const SPREADSHEET_ID = PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID');
const SLACK_WEBHOOK  = PropertiesService.getScriptProperties().getProperty('SLACK_WEBHOOK');

const QUIET_START = 21;  // 24‑h clock
const QUIET_END   = 5;
const SPEND_MULT  = 1.3; // 130 % of daily budget
const CPA_DELTA   = 0.60;
const TZ          = AdsApp.currentAccount().getTimeZone();

function main(){
  const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheetByName('Log');
  const hr    = Number(Utilities.formatDate(new Date(), TZ, 'H'));
  const quiet = (hr >= QUIET_START || hr < QUIET_END);

  try{
    const cIter = AdsApp.campaigns()
      .withCondition("Status = ENABLED")
      .withCondition("AdvertisingChannelType = SEARCH")
      .get();

    while (cIter.hasNext()){
      const c = cIter.next();
      const today = c.getStatsFor("TODAY");
      const spent = today.getCost();
      const convs = today.getConversions();
      const budget= c.getBudget().getAmount();

      /* --- Spend guard --- */
      if (quiet && spent > budget * SPEND_MULT){
        c.pause();
        alertSlack(`⏸ Paused ${c.getName()} – spent $${spent.toFixed(2)} (>${SPEND_MULT}× budget)`);
        log(sheet, c.getName(), 'Paused', spent);
      }

      /* --- CPA anomaly --- */
      const cpa7 = safeDivide(
        c.getStatsFor("LAST_7_DAYS").getCost(),
        c.getStatsFor("LAST_7_DAYS").getConversions());
      const cpa4 = (convs > 0) ? spent / convs : null;

      if (cpa7 && cpa4 && cpa4 > cpa7 * (1 + CPA_DELTA)){
        alertSlack(`⚠️ CPA spike in ${c.getName()} – $${cpa4.toFixed(2)} vs $${cpa7.toFixed(2)}`);
        log(sheet, c.getName(), 'CPA Alert', cpa4);
      }
    }
  }catch(e){
    alertSlack(`❌ Script error: ${e.message}`);
    throw e; // fail Preview so user sees stack trace
  }
}

function alertSlack(msg){
  try{
    UrlFetchApp.fetch(SLACK_WEBHOOK,{
      method:'post', contentType:'application/json',
      payload:JSON.stringify({text:msg}), muteHttpExceptions:false});
  }catch(resp){
    Logger.log('Slack alert failed: ' + resp);
  }
}

function log(sheet, name, action, metric){
  sheet.appendRow([new Date(), name, action, metric]);
}

function safeDivide(a,b){return b? a/b : 0;}
